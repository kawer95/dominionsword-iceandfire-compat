package com.arxyt.dominionsword.iceandfirecompat.control;

import com.arxyt.dominionsword.api.DominionTargeting;
import com.arxyt.dominionsword.control.PlayerControl;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single flight actuator. Behaviours produce intents; this class alone writes airborne velocity. */
public final class DragonFlightController {
    private static final double HOVER_DISTANCE_BASE = 20.0D;
    private static final double HOVER_HEIGHT_BASE = 10.0D;
    private static final double HOVER_HEIGHT_PER_STAGE = 2.0D;
    private static final double TAKEOFF_ALTITUDE = 10.0D;
    private static final double SEPARATION_RADIUS = 12.0D;
    private static final double ARRIVAL_HORIZONTAL = 3.5D;
    private static final long FIRE_INTERVAL = 5L;
    private static final long HOSTILE_QUERY_INTERVAL = 10L;
    private static final int EMERGENCY_THRESHOLD = 100;

    private DragonFlightController() {}

    public static void reset(EntityDragonBase dragon) {
        DragonFlightRegistry.remove(dragon);
        if (dragon != null) dragon.setBreathingFire(false);
    }

    public static void tick(EntityDragonBase dragon) {
        if (dragon == null || dragon.level().isClientSide() || !DragonRideState.isControlled(dragon)) return;
        DragonFlightRegistry.RuntimeState state = DragonFlightRegistry.state(dragon);
        long now = dragon.level().getGameTime();
        state.lastTick = now;
        if (dragon.isRemoved() || !dragon.isAlive() || dragon.isModelDead()) {
            DragonAutopilot.forceEndControl(dragon);
            return;
        }
        DragonRideState.Phase phase = DragonRideState.phase(dragon);
        if (dragon.onGround() && phase == DragonRideState.Phase.LANDING) {
            finishLanding(dragon);
            return;
        }
        if (dragon.onGround() && phase == DragonRideState.Phase.GROUND && !dragon.isFlying() && !dragon.isHovering()) return;
        if (phase == DragonRideState.Phase.TAKEOFF && !dragon.isFlying()) {
            dragon.setFlying(true);
            dragon.setHovering(false);
        }
        Intent intent = switch (phase) {
            case TAKEOFF -> takeoff(dragon, state);
            case LANDING -> landing(dragon, state);
            default -> behaviour(dragon, state);
        };
        applyIntent(dragon, state, intent);
        state.lastPosition = dragon.position();
    }

    private static Intent behaviour(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        return switch (DragonRideState.mission(dragon)) {
            case HOVER_ATTACK -> hoverAttack(dragon, state);
            case ORBIT -> orbit(dragon, state);
            case STRAFE_APPROACH, STRAFE_RUN, STRAFE_EGRESS -> strafe(dragon, state);
            case AREA_HOLD, EMERGENCY_HOVER -> areaHold(dragon, state);
            default -> transit(dragon, state);
        };
    }

    private static Intent takeoff(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!Double.isFinite(state.takeoffStartY)) state.takeoffStartY = dragon.getY();
        double targetY = DragonMoveMath.resolveLiftAltitude(state.takeoffStartY, dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight());
        if (dragon.getY() >= targetY - 0.5D) {
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
            state.takeoffStartY = Double.NaN;
            return behaviour(dragon, state);
        }
        return intentTo(dragon, new Vec3(dragon.getX(), targetY, dragon.getZ()), DragonMoveMath.CRUISE_SPEED * 0.5D, null);
    }

    private static Intent landing(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!DragonRideState.hasLandingSpot(dragon)) return Intent.stop();
        Vec3 spot = DragonRideState.landingSpot(dragon);
        double horizontal = Math.hypot(spot.x - dragon.getX(), spot.z - dragon.getZ());
        if (horizontal > 3.0D || dragon.getY() > spot.y + 2.0D) {
            Vec3 approach = new Vec3(spot.x, spot.y + 10.0D, spot.z);
            return intentTo(dragon, approach, DragonMoveMath.COMBAT_SPEED, null);
        }
        return Intent.velocity(new Vec3(0.0D, -0.25D, 0.0D), null);
    }

    private static Intent transit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!DragonRideState.hasTask(dragon)) return Intent.stop();
        Vec3 task = DragonRideState.task(dragon);
        if (!finite(task)) {
            DragonAutopilot.forceEndControl(dragon);
            return Intent.stop();
        }
        double horizontal = Math.hypot(task.x - dragon.getX(), task.z - dragon.getZ());
        if (horizontal <= ARRIVAL_HORIZONTAL) {
            if (DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null) DragonAutopilot.beginLanding(dragon, task);
            return Intent.stop();
        }
        double y = DragonMoveMath.resolveCruiseAltitude(task.y, dragon.getY(), dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight());
        Vec3 airGoal = new Vec3(task.x, y, task.z);
        ensurePath(dragon, state, airGoal);
        // Planning is incremental.  A still-running job is not a failed route and must not
        // advance the emergency counter merely because it needs another server tick.
        if (state.pathJob != null) return Intent.stop();
        if (state.path.isEmpty()) return noPath(dragon, state);
        Vec3 lookAhead = DragonGuidance.lookAhead(dragon, airGoal, state);
        return intentTo(dragon, lookAhead, DragonMoveMath.CRUISE_SPEED * flightModifier(dragon), null);
    }

    private static void ensurePath(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, Vec3 goal) {
        long now = dragon.level().getGameTime();
        boolean goalChanged = state.lastGoal == null || state.lastGoal.distanceToSqr(goal) > 16.0D;
        boolean stale = state.path.isEmpty() && state.pathJob == null
                && (state.replanTick == 0L || now - state.replanTick >= 20L);
        boolean offPath = !state.path.isEmpty() && distanceToRemainingPath(dragon.position(), state) > 64.0D;
        if ((goalChanged || stale || offPath || state.stalledTicks >= 20) && state.pathJob == null) {
            state.pathJob = DragonPathPlanner.create(dragon, goal);
            state.path = List.of();
            state.pathIndex = 0;
            state.lastGoal = goal;
            state.replanTick = now;
            state.stalledTicks = 0;
        }
        if (state.pathJob != null) {
            List<Vec3> result = DragonPathPlanner.advance(dragon, state.pathJob);
            if (result != null) {
                state.pathJob = null;
                state.path = result;
                state.pathIndex = 0;
                if (!result.isEmpty()) state.noPathTicks = 0;
            }
        }
        if (state.lastPosition != null && dragon.position().distanceToSqr(state.lastPosition) < 0.0625D && state.speed > 0.2D) state.stalledTicks++;
        else state.stalledTicks = 0;
    }

    private static double distanceToRemainingPath(Vec3 position, DragonFlightRegistry.RuntimeState state) {
        double best = Double.MAX_VALUE;
        for (int i = state.pathIndex; i < state.path.size(); i++) best = Math.min(best, position.distanceToSqr(state.path.get(i)));
        return best;
    }

    private static Intent noPath(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        state.noPathTicks++;
        if (state.noPathTicks >= EMERGENCY_THRESHOLD) {
            DragonRideState.setMission(dragon, DragonRideState.Mission.EMERGENCY_HOVER);
            stopBreath(dragon);
        }
        return Intent.stop();
    }

    private static Intent hoverAttack(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        LivingEntity target = dragon.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved() || target.level() != dragon.level()) {
            stopBreath(dragon);
            DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
            return Intent.stop();
        }
        Vec3 targetPos = target.position();
        if (state.hoverAnchor == null) {
            Vec3 radial = dragon.position().subtract(targetPos);
            radial = DragonMoveMath.normalizeOr(new Vec3(radial.x, 0.0D, radial.z), state.lastSafeDirection);
            double radius = HOVER_DISTANCE_BASE + dragon.getBbWidth() * 0.5D;
            state.hoverAnchor = targetPos.add(radial.scale(radius));
        }
        double radius = HOVER_DISTANCE_BASE + dragon.getBbWidth() * 0.5D;
        Vec3 radial = state.hoverAnchor.subtract(targetPos);
        radial = DragonMoveMath.normalizeOr(new Vec3(radial.x, 0.0D, radial.z), state.lastSafeDirection);
        state.hoverAnchor = new Vec3(targetPos.x + radial.x * radius,
                targetPos.y + HOVER_HEIGHT_BASE + HOVER_HEIGHT_PER_STAGE * dragon.getDragonStage(), targetPos.z + radial.z * radius);
        fireAt(dragon, state, target, targetPos);
        return intentTo(dragon, state.hoverAnchor, DragonMoveMath.COMBAT_SPEED * flightModifier(dragon), targetPos);
    }

    private static Intent orbit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon) + 12.0D);
        Vec3 radial = new Vec3(dragon.getX() - center.x, 0.0D, dragon.getZ() - center.z);
        radial = DragonMoveMath.normalizeOr(radial, state.lastSafeDirection);
        int sign = (dragon.getUUID().hashCode() & 1) == 0 ? 1 : -1;
        Vec3 tangent = new Vec3(-radial.z * sign, 0.0D, radial.x * sign);
        double radialError = Math.hypot(dragon.getX() - center.x, dragon.getZ() - center.z) - radius;
        double desiredY = center.y + HOVER_HEIGHT_BASE + HOVER_HEIGHT_PER_STAGE * dragon.getDragonStage();
        Vec3 velocity = tangent.scale(DragonMoveMath.COMBAT_SPEED).add(radial.scale(-radialError * 0.08D))
                .add(0.0D, (desiredY - dragon.getY()) * 0.08D, 0.0D);
        if (dragon.level().getGameTime() - state.lastHostileQueryTick >= HOSTILE_QUERY_INTERVAL) {
            state.lastHostileQueryTick = dragon.level().getGameTime();
            LivingEntity target = nearestHostile(dragon, center);
            if (target != null) fireAt(dragon, state, target, target.position());
        }
        return Intent.velocity(DragonMoveMath.clampSpeed(velocity, DragonMoveMath.COMBAT_SPEED * flightModifier(dragon)), center);
    }

    private static Intent areaHold(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        stopBreath(dragon);
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        Vec3 anchor = new Vec3(center.x, center.y + HOVER_HEIGHT_BASE + HOVER_HEIGHT_PER_STAGE * dragon.getDragonStage(), center.z);
        return intentTo(dragon, anchor, DragonMoveMath.HOVER_SPEED, null);
    }

    private static Intent strafe(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        if (state.strafeAxis == null) state.strafeAxis = DragonMoveMath.normalizeOr(new Vec3(center.x - dragon.getX(), 0.0D, center.z - dragon.getZ()), state.lastSafeDirection);
        Vec3 axis = state.strafeAxis;
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon));
        long now = dragon.level().getGameTime();
        if (state.strafeStageStartTick == 0L) state.strafeStageStartTick = now;
        if (now - state.strafeStageStartTick > 200L) {
            stopBreath(dragon);
            DragonRideState.setMission(dragon, DragonRideState.Mission.EMERGENCY_HOVER);
            return Intent.stop();
        }
        if (state.strafeStage == 0) {
            Vec3 entry = center.add(axis.scale(-(radius + 24.0D))).add(0.0D, HOVER_HEIGHT_BASE, 0.0D);
            if (dragon.position().distanceToSqr(entry) < 36.0D) { state.strafeStage = 1; state.strafeStageStartTick = now; }
            return intentTo(dragon, entry, DragonMoveMath.STRAFE_SPEED * flightModifier(dragon), center);
        }
        double along = (dragon.getX() - center.x) * axis.x + (dragon.getZ() - center.z) * axis.z;
        if (state.strafeStage == 1) {
            if (Math.abs(along) <= radius + 2.0D) {
                LivingEntity target = nearestHostile(dragon, center);
                if (target != null) fireAt(dragon, state, target, target.position());
            } else stopBreath(dragon);
            if (along > radius + 8.0D) { state.strafeStage = 2; state.strafeStageStartTick = now; stopBreath(dragon); }
            return Intent.velocity(axis.scale(DragonMoveMath.STRAFE_SPEED * flightModifier(dragon)), center);
        }
        if (along > radius + 32.0D) {
            stopBreath(dragon);
            state.resetManeuver();
            DragonRideState.setMission(dragon, DragonRideState.Mission.AREA_HOLD);
        }
        return Intent.velocity(axis.scale(DragonMoveMath.STRAFE_SPEED * flightModifier(dragon)), null);
    }

    private static void applyIntent(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, Intent intent) {
        Vec3 requested = intent.velocity;
        requested = requested.add(DragonFlightRegistry.separation(dragon, SEPARATION_RADIUS));
        Vec3 requestedDir = DragonMoveMath.normalizeOr(requested, state.lastSafeDirection);
        Vec3 safe = DragonGuidance.steer(dragon, requestedDir, state);
        if (safe == null) {
            applyMotion(dragon, state, DragonMoveMath.normalizeOr(dragon.getDeltaMovement(), state.lastSafeDirection), 0.0D);
            return;
        }
        applyMotion(dragon, state, safe, requested.length());
    }

    private static void applyMotion(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, Vec3 desiredDirection, double requestedSpeed) {
        Vec3 currentVelocity = dragon.getDeltaMovement();
        double currentSpeed = currentVelocity.length();
        Vec3 currentDirection = DragonMoveMath.normalizeOr(currentVelocity, state.lastSafeDirection);
        double targetSpeed = DragonMoveMath.turnLimitedSpeed(requestedSpeed, currentDirection, desiredDirection);
        double step = targetSpeed >= currentSpeed ? DragonMoveMath.ACCELERATION : DragonMoveMath.BRAKE_DECELERATION;
        double nextSpeed = DragonMoveMath.approach(currentSpeed, targetSpeed, step);
        Vec3 nextDirection = DragonMoveMath.turnToward(currentDirection, desiredDirection, DragonMoveMath.yawRate(dragon.getDragonStage()), DragonMoveMath.PITCH_RATE);
        Vec3 nextVelocity = nextDirection.scale(nextSpeed);
        state.speed = nextSpeed;
        state.lastSafeDirection = nextDirection;
        dragon.setDeltaMovement(nextVelocity);
        double yaw = Math.toDegrees(Math.atan2(-nextDirection.x, nextDirection.z));
        dragon.setYRot((float) yaw);
        dragon.setYHeadRot((float) yaw);
        dragon.yBodyRot = (float) yaw;
        dragon.setXRot((float) DragonMoveMath.clampPitch(DragonMoveMath.pitchTo(nextDirection)));
    }

    private static void fireAt(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, LivingEntity target, Vec3 targetPos) {
        long now = dragon.level().getGameTime();
        if (now - state.lastFireTick < FIRE_INTERVAL || !dragon.hasLineOfSight(target) || !hostile(dragon, target)) { stopBreath(dragon); return; }
        dragon.setBreathingFire(true);
        dragon.stimulateFire(targetPos.x, targetPos.y + target.getBbHeight() * 0.5D, targetPos.z, 1);
        state.lastFireTick = now;
    }

    private static void stopBreath(EntityDragonBase dragon) { dragon.setBreathingFire(false); }

    private static boolean hostile(EntityDragonBase dragon, LivingEntity candidate) {
        ServerPlayer commander = commander(dragon);
        if (commander == null) return false;
        List<Entity> allies = new ArrayList<>(PlayerControl.mobs(commander));
        allies.add(dragon);
        return DominionTargeting.isHostileCandidate(commander, allies, candidate);
    }

    private static LivingEntity nearestHostile(EntityDragonBase dragon, Vec3 center) {
        ServerPlayer commander = commander(dragon);
        if (commander == null || !(dragon.level() instanceof ServerLevel level)) return null;
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon));
        double halfHeight = Math.max(1.0D, DragonRideState.aoeHalfHeight(dragon));
        AABB box = new AABB(center.x - radius, center.y - halfHeight, center.z - radius, center.x + radius, center.y + halfHeight, center.z + radius);
        List<Entity> allies = new ArrayList<>(PlayerControl.mobs(commander));
        allies.add(dragon);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box, entity -> hostileCylinder(center, radius, halfHeight, entity)
                && DominionTargeting.isHostileCandidate(commander, allies, entity))) {
            double distance = dragon.distanceToSqr(candidate);
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        return best;
    }

    private static boolean hostileCylinder(Vec3 center, double radius, double halfHeight, LivingEntity entity) {
        double dx = entity.getX() - center.x, dz = entity.getZ() - center.z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(entity.getY() - center.y) <= halfHeight;
    }

    private static ServerPlayer commander(EntityDragonBase dragon) {
        UUID id = PlayerControl.controller(dragon);
        if (id == null || !(dragon.level() instanceof ServerLevel level)) return null;
        return level.getServer().getPlayerList().getPlayer(id);
    }

    private static void finishLanding(EntityDragonBase dragon) {
        stopBreath(dragon);
        dragon.setFlying(false);
        dragon.setHovering(false);
        DragonRideState.clearLandingSpot(dragon);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        if (DragonRideState.hasTask(dragon)) DragonRideState.clearTask(dragon);
    }

    private static boolean finite(Vec3 value) { return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }

    private static double flightModifier(EntityDragonBase dragon) {
        double modifier = dragon.getFlightSpeedModifier();
        return Double.isFinite(modifier) ? Math.max(0.25D, Math.min(2.0D, modifier)) : 1.0D;
    }

    private static Intent intentTo(EntityDragonBase dragon, Vec3 position, double speed, Vec3 facing) {
        Vec3 delta = position.subtract(dragon.position());
        double length = delta.length();
        return Intent.velocity(length < 1.0E-6D ? Vec3.ZERO : delta.scale(speed / length), facing);
    }

    private record Intent(Vec3 velocity, Vec3 facing) {
        static Intent velocity(Vec3 velocity, Vec3 facing) { return new Intent(velocity, facing); }
        static Intent stop() { return new Intent(Vec3.ZERO, null); }
    }
}

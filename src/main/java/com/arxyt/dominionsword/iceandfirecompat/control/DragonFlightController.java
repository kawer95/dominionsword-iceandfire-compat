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
import java.util.Map;
import java.util.UUID;

/**
 * Airborne flight controller for Dominion-controlled dragons. Runs from the FlightMoveHelper
 * mixin every tick (correct move-control phase), reads the persistent mission, plans paths,
 * steers with look-ahead and writes the final velocity/yaw/pitch. Native hover/random-flight
 * writes are neutralized by mixins while controlled.
 */
public final class DragonFlightController {
    private static final double HOVER_DISTANCE_BASE = 20.0D;
    private static final double HOVER_HEIGHT_BASE = 10.0D;
    private static final double HOVER_HEIGHT_PER_STAGE = 2.0D;
    private static final double YAW_ERROR_LIMIT = 25.0D;
    private static final double ARRIVAL_HORIZONTAL = 3.5D;
    private static final double ARRIVAL_VERTICAL = 2.5D;
    private static final double ORBIT_MARGIN = 12.0D;
    private static final double STRAFE_BEHIND = 24.0D;
    private static final double STRAFE_EGRESS = 32.0D;
    private static final double SEPARATION_RADIUS = 12.0D;
    private static final double TAKEOFF_ALTITUDE = 10.0D;
    private static final long FIRE_INTERVAL_TICKS = 5L;
    private static final long ORBIT_QUERY_INTERVAL = 10L;
    private static final int REPLAN_INTERVAL_TICKS = 20;
    private static final double REPLAN_GOAL_DELTA = 4.0D;
    private static final double REPLAN_DEVIATION = 8.0D;
    private static final int EMERGENCY_THRESHOLD = 100;

    private DragonFlightController() {
    }

    public static void tick(EntityDragonBase dragon) {
        if (dragon == null || !DragonRideState.isControlled(dragon)) return;
        long now = dragon.level().getGameTime();
        DragonFlightRegistry.prune(now);
        DragonFlightRegistry.RuntimeState state = DragonFlightRegistry.state(dragon);
        state.lastTick = now;
        if (dragon.isModelDead() || !dragon.isAlive() || dragon.isRemoved()) return;

        DragonRideState.Phase phase = DragonRideState.phase(dragon);
        if (dragon.onGround()) {
            if (phase == DragonRideState.Phase.LANDING) {
                dragon.setFlying(false);
                dragon.setHovering(false);
                DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
                if (DragonRideState.hasTask(dragon) && arrived(dragon, DragonRideState.task(dragon))) {
                    DragonRideState.clearTask(dragon);
                    DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
                }
                DragonRideState.clearLandingSpot(dragon);
                return;
            }
            if (phase != DragonRideState.Phase.TAKEOFF && !dragon.isFlying() && !dragon.isHovering()) return;
        }
        if (phase == DragonRideState.Phase.TAKEOFF && !dragon.isFlying()) {
            dragon.setFlying(true);
            dragon.setHovering(false);
        }

        Vec3 velocity = dispatch(dragon, state);
        velocity = velocity.add(separation(dragon));
        velocity = DragonMoveMath.clampSpeed(velocity, maxSpeed(dragon));
        applyMotion(dragon, velocity);
        if (phase != DragonRideState.Phase.LANDING) {
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
        }
        state.lastPosition = dragon.position();
        state.emergencyTicks = Math.max(0, state.emergencyTicks - 1);
    }

    private static Vec3 dispatch(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        DragonRideState.Phase phase = DragonRideState.phase(dragon);
        if (phase == DragonRideState.Phase.TAKEOFF) return takeoff(dragon, state);
        if (phase == DragonRideState.Phase.LANDING) return landing(dragon, state);
        return switch (DragonRideState.mission(dragon)) {
            case HOVER_ATTACK -> hoverAttack(dragon, state);
            case ORBIT -> orbit(dragon, state);
            case STRAFE_APPROACH, STRAFE_RUN, STRAFE_EGRESS -> strafe(dragon, state);
            case EMERGENCY_HOVER -> hoverInPlace(dragon, state);
            default -> transit(dragon, state);
        };
    }

    private static Vec3 transit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!DragonRideState.hasTask(dragon)) return hoverInPlace(dragon, state);
        Vec3 goal = DragonRideState.task(dragon);
        boolean replan = state.path.isEmpty()
                || state.lastGoal == null || state.lastGoal.distanceToSqr(goal) > REPLAN_GOAL_DELTA * REPLAN_GOAL_DELTA
                || (dragon.level().getGameTime() - state.replanTick >= REPLAN_INTERVAL_TICKS
                    && dragon.position().distanceToSqr(goal) > REPLAN_DEVIATION * REPLAN_DEVIATION);
        if (replan) {
            state.lastGoal = goal;
            state.replanTick = dragon.level().getGameTime();
            state.path = DragonPathPlanner.plan(dragon, goal, state);
        }
        if (state.path.isEmpty()) {
            state.emergencyTicks++;
            if (state.emergencyTicks >= EMERGENCY_THRESHOLD) {
                DragonRideState.setMission(dragon, DragonRideState.Mission.EMERGENCY_HOVER);
            }
            return hoverInPlace(dragon, state);
        }
        if (arrived(dragon, goal)) {
            boolean auto = DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null;
            if (auto) {
                DragonAutopilot.beginLanding(dragon, goal);
                return landing(dragon, state);
            }
            return hoverInPlace(dragon, state);
        }
        Vec3 dir = DragonGuidance.steer(dragon, goal, state);
        if (dir.lengthSqr() < 1.0E-6D) return Vec3.ZERO;
        return dir.scale(DragonMoveMath.CRUISE_SPEED);
    }

    private static Vec3 takeoff(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (state.takeoffStartY <= 0.0D) state.takeoffStartY = dragon.getY();
        if (dragon.getY() - state.takeoffStartY >= TAKEOFF_ALTITUDE) {
            state.takeoffStartY = 0.0D;
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
        }
        return new Vec3(0.0D, DragonMoveMath.CRUISE_SPEED * 0.5D, 0.0D);
    }

    private static Vec3 landing(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 spot = DragonRideState.hasLandingSpot(dragon) ? DragonRideState.landingSpot(dragon) : null;
        if (spot == null) return hoverInPlace(dragon, state);
        double horizontal = Math.hypot(spot.x - dragon.getX(), spot.z - dragon.getZ());
        if (horizontal > 5.0D || dragon.getY() > spot.y + 10.0D) {
            Vec3 toward = spot.subtract(dragon.position());
            double length = toward.length();
            return length < 1.0E-4D ? Vec3.ZERO : toward.scale(DragonMoveMath.CRUISE_SPEED * 0.7D / length);
        }
        return new Vec3(0.0D, -Math.min(0.35D, DragonMoveMath.CRUISE_SPEED * 0.5D), 0.0D);
    }

    private static Vec3 hoverAttack(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        LivingEntity target = dragon.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved()) {
            DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
            return hoverInPlace(dragon, state);
        }
        Vec3 targetPos = target.position();
        Vec3 delta = dragon.position().subtract(targetPos);
        double horizontal = Math.hypot(delta.x, delta.z);
        double distance = HOVER_DISTANCE_BASE + dragon.getBbWidth() * 0.5D;
        Vec3 hover = targetPos;
        if (horizontal > 1.0E-4D) {
            hover = targetPos.add(delta.scale(distance / horizontal));
        }
        hover = new Vec3(hover.x, targetPos.y + HOVER_HEIGHT_BASE + HOVER_HEIGHT_PER_STAGE * dragon.getDragonStage(), hover.z);
        Vec3 velocity = DragonMoveMath.dampedHover(dragon.position(), hover, dragon.getDeltaMovement(),
                DragonMoveMath.COMBAT_SPEED);
        long now = dragon.level().getGameTime();
        if (now - state.lastFireTick >= FIRE_INTERVAL_TICKS
                && dragon.hasLineOfSight(target)
                && yawError(dragon, targetPos) <= YAW_ERROR_LIMIT
                && hostile(dragon, target)) {
            dragon.stimulateFire(targetPos.x, targetPos.y + target.getBbHeight() * 0.5D, targetPos.z, 1);
            state.lastFireTick = now;
        }
        return velocity;
    }

    private static Vec3 orbit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon) + ORBIT_MARGIN);
        int direction = (dragon.getUUID().hashCode() & 1) == 0 ? 1 : -1;
        double angle = Math.atan2(dragon.getZ() - center.z, dragon.getX() - center.x);
        double step = DragonMoveMath.COMBAT_SPEED / radius * direction;
        double next = angle + step;
        Vec3 desired = new Vec3(center.x + Math.cos(next) * radius, center.y + 2.0D, center.z + Math.sin(next) * radius);
        Vec3 velocity = DragonMoveMath.dampedHover(dragon.position(), desired, dragon.getDeltaMovement(),
                DragonMoveMath.COMBAT_SPEED);
        long now = dragon.level().getGameTime();
        if (now - state.lastFireTick >= ORBIT_QUERY_INTERVAL) {
            LivingEntity target = nearestHostile(dragon, center);
            if (target != null && dragon.hasLineOfSight(target)) {
                Vec3 pos = target.position();
                dragon.stimulateFire(pos.x, pos.y + target.getBbHeight() * 0.5D, pos.z, 1);
                state.lastFireTick = now;
            }
        }
        return velocity;
    }

    private static Vec3 strafe(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        Vec3 axis = state.strafeAxis;
        if (axis == null) {
            Vec3 delta = center.subtract(dragon.position());
            double length = Math.hypot(delta.x, delta.z);
            axis = length < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : delta.scale(1.0D / length);
            state.strafeAxis = axis;
        }
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon));
        switch (state.strafeStage) {
            case 0 -> {
                Vec3 entry = center.add(axis.scale(-(radius + STRAFE_BEHIND)));
                if (dragon.position().distanceToSqr(entry) < 36.0D) {
                    state.strafeStage = 1;
                }
                return toward(dragon.position(), new Vec3(entry.x, center.y + 4.0D, entry.z),
                        DragonMoveMath.STRAFE_SPEED);
            }
            case 1 -> {
                Vec3 pass = dragon.position().subtract(center);
                double along = pass.x * axis.x + pass.z * axis.z;
                if (along > radius + 8.0D) state.strafeStage = 2;
                return new Vec3(axis.x * DragonMoveMath.STRAFE_SPEED, 0.0D, axis.z * DragonMoveMath.STRAFE_SPEED);
            }
            default -> {
                if (dragon.position().distanceTo(center) >= STRAFE_EGRESS) {
                    state.strafeStage = 0;
                    state.strafeAxis = null;
                    DragonRideState.setMission(dragon, DragonRideState.Mission.HOVER_ATTACK);
                }
                return new Vec3(axis.x * DragonMoveMath.STRAFE_SPEED, 0.0D, axis.z * DragonMoveMath.STRAFE_SPEED);
            }
        }
    }

    private static Vec3 emergencyHover(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        return hoverInPlace(dragon, state);
    }

    private static Vec3 hoverInPlace(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        return DragonMoveMath.dampedHover(dragon.position(), dragon.position(), dragon.getDeltaMovement(),
                DragonMoveMath.HOVER_SPEED);
    }

    private static Vec3 separation(EntityDragonBase dragon) {
        Vec3 push = Vec3.ZERO;
        for (Map.Entry<UUID, Vec3> entry : DragonFlightRegistry.positions(dragon).entrySet()) {
            Vec3 other = entry.getValue();
            double distance = dragon.position().distanceTo(other);
            if (distance >= SEPARATION_RADIUS || distance < 1.0E-4D) continue;
            double strength = (1.0D - distance / SEPARATION_RADIUS) * 0.12D;
            push = push.add(dragon.position().subtract(other).normalize().scale(strength));
        }
        return push;
    }

    private static void applyMotion(EntityDragonBase dragon, Vec3 velocity) {
        DragonFlightRegistry.RuntimeState state = DragonFlightRegistry.state(dragon);
        double currentSpeed = state.speed;
        double targetSpeed = velocity.length();
        double step = targetSpeed >= currentSpeed ? DragonMoveMath.ACCELERATION : DragonMoveMath.BRAKE_DECELERATION;
        state.speed = DragonMoveMath.approach(currentSpeed, targetSpeed, step);
        Vec3 dir = targetSpeed < 1.0E-4D ? Vec3.ZERO : velocity.scale(1.0D / targetSpeed);
        dragon.setDeltaMovement(dir.scale(state.speed));
        double horizontal = Math.hypot(dragon.getDeltaMovement().x, dragon.getDeltaMovement().z);
        if (horizontal > 0.01D) {
            double targetYaw = Math.toDegrees(Math.atan2(-dragon.getDeltaMovement().x, dragon.getDeltaMovement().z));
            double yaw = DragonMoveMath.approachDegrees(dragon.getYRot(), targetYaw,
                    DragonMoveMath.yawRate(dragon.getDragonStage()));
            dragon.setYRot((float) yaw);
            dragon.setYHeadRot((float) yaw);
            dragon.yBodyRot = (float) yaw;
        }
        if (targetSpeed > 1.0E-4D) {
            double targetPitch = DragonMoveMath.clampPitch(DragonMoveMath.pitchTo(velocity));
            dragon.setXRot((float) DragonMoveMath.approach(dragon.getXRot(), targetPitch, DragonMoveMath.PITCH_RATE));
        }
    }

    private static double maxSpeed(EntityDragonBase dragon) {
        double base = switch (DragonRideState.mission(dragon)) {
            case HOVER_ATTACK, ORBIT, EMERGENCY_HOVER -> DragonMoveMath.COMBAT_SPEED;
            case STRAFE_APPROACH, STRAFE_RUN, STRAFE_EGRESS -> DragonMoveMath.STRAFE_SPEED;
            default -> DragonMoveMath.CRUISE_SPEED;
        };
        return base * dragon.getFlightSpeedModifier();
    }

    private static boolean arrived(EntityDragonBase dragon, Vec3 goal) {
        double horizontal = Math.hypot(goal.x - dragon.getX(), goal.z - dragon.getZ());
        return horizontal <= ARRIVAL_HORIZONTAL && Math.abs(goal.y - dragon.getY()) <= ARRIVAL_VERTICAL;
    }

    private static double yawError(EntityDragonBase dragon, Vec3 target) {
        double targetYaw = Math.toDegrees(Math.atan2(-(target.x - dragon.getX()), target.z - dragon.getZ()));
        return Math.abs(DragonMoveMath.wrapDegrees(targetYaw - dragon.getYRot()));
    }

    private static boolean hostile(EntityDragonBase dragon, LivingEntity candidate) {
        ServerPlayer commander = commander(dragon);
        if (commander == null) return true;
        List<? extends Entity> allies = new ArrayList<>(PlayerControl.mobs(commander));
        return DominionTargeting.isHostileCandidate(commander, allies, candidate);
    }

    private static LivingEntity nearestHostile(EntityDragonBase dragon, Vec3 center) {
        ServerPlayer commander = commander(dragon);
        if (commander == null || !(dragon.level() instanceof ServerLevel level)) return null;
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon));
        double halfHeight = Math.max(1.0D, DragonRideState.aoeHalfHeight(dragon));
        AABB box = new AABB(center.x - radius, center.y - halfHeight, center.z - radius,
                center.x + radius, center.y + halfHeight, center.z + radius);
        List<? extends Entity> allies = new ArrayList<>(PlayerControl.mobs(commander));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> DominionTargeting.isHostileCandidate(commander, allies, entity))) {
            double distance = dragon.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static ServerPlayer commander(EntityDragonBase dragon) {
        UUID controller = PlayerControl.controller(dragon);
        if (controller == null || !(dragon.level() instanceof ServerLevel level)) return null;
        return level.getServer().getPlayerList().getPlayer(controller);
    }

    private static Vec3 toward(Vec3 from, Vec3 to, double speed) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        return length < 1.0E-4D ? Vec3.ZERO : delta.scale(speed / length);
    }
}

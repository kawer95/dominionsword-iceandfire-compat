package com.arxyt.dominionsword.iceandfirecompat.control;

import com.arxyt.dominionsword.api.DominionTargeting;
import com.arxyt.dominionsword.control.PlayerControl;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single flight actuator. Behaviours produce intents; this class alone writes airborne velocity. */
public final class DragonFlightController {
    private static final double HOVER_DISTANCE_BASE = 20.0D;
    private static final double TAKEOFF_ALTITUDE = 10.0D;
    private static final double SEPARATION_RADIUS = 12.0D;
    private static final double ARRIVAL_HORIZONTAL = 3.5D;
    private static final long FIRE_INTERVAL = 5L;
    private static final long HOSTILE_QUERY_INTERVAL = 10L;
    private static final int EMERGENCY_THRESHOLD = 100;
    /** Orbit breath keeps circling while hostiles are in the cylinder; with no hostiles it must
     *  leave the pattern and land instead of orbiting forever. */
    private static final long ORBIT_IDLE_EXIT_TICKS = 100L;
    /** Air breath fires continuously for a few seconds (45 ticks), then pauses (30 ticks),
     *  then repeats - a sustained spray, not one-shot puffs. */
    private static final int BREATH_BURST_TICKS = 45;
    private static final int BREATH_GAP_TICKS = 30;

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
        // updateDragonServer (which normally calls updatePitch) is suppressed for controlled dragons;
        // keep dragonPitch live so the rendered body pitches with vertical motion and auto-levels.
        // A gentle sine wave during cruise makes the neck/head undulate so the rider on the neck
        // visibly follows the motion, exactly like a player-controlled dragon.
        if (state.lastPosition != null) {
            double verticalDelta = state.lastPosition.y - dragon.getY();
            double basePitch = Math.max(-20.0D, Math.min(20.0D, verticalDelta * 10.0D));
            double wave = (dragon.isFlying() && !dragon.isHovering())
                    ? Math.sin(dragon.level().getGameTime() * 0.1D) * 4.0D : 0.0D;
            dragon.setDragonPitch((float) (basePitch + wave));
        }
        dragonSounds(dragon, state);
        if (phase == DragonRideState.Phase.GROUND && !DragonRideState.hasTask(dragon)
                && DragonRideState.mission(dragon) == DragonRideState.Mission.TRANSIT) {
            // Selection alone is not a movement command. Keep native Ice and Fire AI from
            // independently choosing a flight destination before Dominion receives one.
            DragonAutopilot.holdGround(dragon);
            state.lastPosition = dragon.position();
            return;
        }
        if (dragon.onGround() && phase == DragonRideState.Phase.LANDING) {
            finishLanding(dragon);
            return;
        }
        if (phase == DragonRideState.Phase.GROUND) {
            // A GROUND phase while the dragon is still high in the air means a new command
            // arrived mid-flight.  Redirect in the air instead of slamming the dragon down.
            if (dragon.isFlying() || dragon.isHovering() || dragon.getY() - groundHeight(dragon, state) > 4.0D) {
                DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
                dragon.setFlying(true);
                dragon.setHovering(false);
            } else {
                dragon.setFlying(false);
                dragon.setHovering(false);
                if (DragonRideState.mission(dragon) == DragonRideState.Mission.HOVER_ATTACK) {
                    runGroundAttack(dragon, state);
                } else {
                    runGroundTransit(dragon);
                }
                state.lastPosition = dragon.position();
                return;
            }
        }
        // Any non-ground phase is flight; the flying flag is re-asserted after native writers.
        dragon.setFlying(true);
        dragon.setHovering(false);
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

    /**
     * A normal nearby move order is a ground order, not an implicit takeoff order.  Previously
     * both adapter and task setter called beginTakeoff unconditionally, so even a point a few
     * blocks away produced a ten-block climb followed immediately by a landing search.
     */
    private static void runGroundTransit(EntityDragonBase dragon) {
        dragon.setFlying(false);
        dragon.setHovering(false);
        stopBreath(dragon);
        dragon.getNavigation().stop();
        if (DragonRideState.mission(dragon) != DragonRideState.Mission.TRANSIT || !DragonRideState.hasTask(dragon)) {
            DragonAutopilot.holdGround(dragon);
            return;
        }
        Vec3 task = DragonRideState.task(dragon);
        if (!finite(task)) {
            // A corrupted goal must not force-end control: that clears the flying flags and
            // drops an airborne dragon.  Drop the goal and keep hovering/standing instead.
            DragonRideState.clearTask(dragon);
            return;
        }
        double horizontal = Math.hypot(task.x - dragon.getX(), task.z - dragon.getZ());
        double vertical = task.y - dragon.getY();
        // Auto-control decides per-distance (near = walk, far = fly).  A dragon without a driver
        // is always in auto control.  With auto-control off and a driver present the manual mode
        // is sticky: a grounded dragon walks to any order and never auto-takes-off.
        if (effectiveAuto(dragon) && DragonMoveMath.shouldTakeoff(horizontal, vertical)
                && dragon.hasFlightClearance()) {
            DragonAutopilot.beginTakeoff(dragon);
            return;
        }
        if (horizontal <= ARRIVAL_HORIZONTAL) {
            dragon.setDeltaMovement(Vec3.ZERO);
            return;
        }
        // Native navigation is deliberately not used here.  It lives inside Ice and Fire's AI
        // step and can promote a grounded dragon into its own flight manager.  Dominion is the
        // sole actuator while controlled, including short ground orders.
        double dx = task.x - dragon.getX();
        double dz = task.z - dragon.getZ();
        Vec3 desired = new Vec3(dx, 0.0D, dz).normalize().scale(0.28D);
        // Direct ground velocity, not a damped blend: blending with the previous (possibly
        // native-gravity) velocity kept the dragon crawling sideways at ~0.1 blocks/tick.
        dragon.setDeltaMovement(desired.x, 0.0D, desired.z);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        dragon.setYRot((float) yaw);
        dragon.setYHeadRot((float) yaw);
        dragon.yBodyRot = (float) yaw;
        dragon.setXRot(0.0F);
    }

    /** Ground level below the dragon, found by scanning downward from the dragon's body for the
     *  first solid block (like the helicopter adapters).  A world heightmap above the dragon is
     *  a ceiling, not the ground, so the dragon never treats a roof as its floor.  Cached 20
     *  ticks. */
    private static double groundHeight(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        long now = dragon.level().getGameTime();
        Vec3 position = dragon.position();
        boolean movedColumn = state.terrainFloorOrigin == null
                || Math.abs(position.x - state.terrainFloorOrigin.x) >= 2.0D
                || Math.abs(position.z - state.terrainFloorOrigin.z) >= 2.0D;
        if (!Double.isFinite(state.terrainFloorY) || movedColumn || now - state.terrainFloorTick >= 20L) {
            state.terrainFloorY = scanGroundBelow(dragon);
            state.terrainFloorTick = now;
            state.terrainFloorOrigin = position;
        }
        return state.terrainFloorY;
    }

    private static double scanGroundBelow(EntityDragonBase dragon) {
        net.minecraft.world.level.Level level = dragon.level();
        int blockX = Mth.floor(dragon.getX());
        int blockZ = Mth.floor(dragon.getZ());
        int y = Math.min(Mth.floor(dragon.getY()), level.getMaxBuildHeight() - 1);
        while (y >= level.getMinBuildHeight()) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(blockX, y, blockZ);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
            if (!state.is(net.minecraft.world.level.block.Blocks.BARRIER) && !shape.isEmpty()) {
                return y + shape.max(net.minecraft.core.Direction.Axis.Y);
            }
            y--;
        }
        return level.getMinBuildHeight();
    }

    private static Intent takeoff(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!Double.isFinite(state.takeoffStartY)) state.takeoffStartY = dragon.getY();
        double targetY = Math.min(DragonMoveMath.resolveCeiling(dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight()),
                Math.max(DragonMoveMath.resolveFloor(dragon.level().getMinBuildHeight()), state.takeoffStartY + hoverAltitudeFor(dragon)));
        if (dragon.getY() >= targetY - 0.5D) {
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
            state.takeoffStartY = Double.NaN;
            return behaviour(dragon, state);
        }
        return intentTo(dragon, new Vec3(dragon.getX(), targetY, dragon.getZ()), DragonMoveMath.CRUISE_SPEED * flightModifier(dragon), null);
    }

    private static Intent landing(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        if (!DragonRideState.hasLandingSpot(dragon)) return Intent.stop();
        Vec3 spot = DragonRideState.landingSpot(dragon);
        double horizontal = Math.hypot(spot.x - dragon.getX(), spot.z - dragon.getZ());
        if (horizontal > 3.0D) {
            // Level approach that converges two blocks above the pad.  The old +10 floor made
            // the vertical-descent branch (horizontal <= 3 && y <= spot.y + 2) unreachable: the
            // dragon kept flying level at +10 and circled the pad forever.  Descend during the
            // approach so the final vertical drop is only two blocks.
            double dx = spot.x - dragon.getX();
            double dz = spot.z - dragon.getZ();
            double h = Math.hypot(dx, dz);
            Vec3 desired = new Vec3(dx / h, clampVertical((spot.y + 2.0D - dragon.getY()) * 0.05D), dz / h).normalize();
            // Cap the approach speed so the turn circle (speed / yaw rate) always fits inside
            // the remaining distance; otherwise the same minimum-turn-radius limit cycle that
            // made cruise orbit the goal also keeps the landing approach circling the pad.
            double yawRad = Math.toRadians(DragonMoveMath.yawRate(dragon.getDragonStage()));
            double cruise = DragonMoveMath.CRUISE_SPEED * flightModifier(dragon);
            double approachSpeed = Math.min(cruise, Math.max(0.2D, horizontal * yawRad * 0.5D));
            return Intent.velocity(desired.scale(approachSpeed), null);
        }
        return Intent.velocity(new Vec3(0.0D, -0.25D, 0.0D), null);
    }

    /** Horizontal distance inside which the cruise turn circle cannot converge; auto control
     *  lands here and walks the rest instead of orbiting the goal at turn radius. */
    private static double landingApproachRadius(EntityDragonBase dragon) {
        double speed = DragonMoveMath.CRUISE_SPEED * flightModifier(dragon);
        double yawRad = Math.toRadians(DragonMoveMath.yawRate(dragon.getDragonStage()));
        double turnRadius = speed / Math.max(0.01D, yawRad);
        return Math.max(ARRIVAL_HORIZONTAL, Math.min(48.0D, turnRadius * 2.0D + 8.0D));
    }

    private static Intent transit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        stopBreath(dragon);
        if (!DragonRideState.hasTask(dragon)) return Intent.stop();
        Vec3 task = DragonRideState.task(dragon);
        if (!finite(task)) {
            // A corrupted goal must not force-end control (mid-air drop); clear it and hover.
            DragonRideState.clearTask(dragon);
            return Intent.stop();
        }
        // Hover altitude scales with dragon size: small dragons hover low (~8 blocks), maxed
        // elders hover at the full cruise altitude (24).  The dragon must not chase the terrain
        // upward; obstacles are handled by the safety layer and pathfinder.
        double hover = hoverAltitudeFor(dragon);
        double y = Math.max(task.y + hover, dragon.getY() - DragonMoveMath.CRUISE_FLOOR_BELOW_DRAGON);
        y = Math.min(DragonMoveMath.resolveCeiling(dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight()), y);
        Vec3 airGoal = new Vec3(task.x, y, task.z);
        double horizontal = Math.hypot(task.x - dragon.getX(), task.z - dragon.getZ());
        if (horizontal <= ARRIVAL_HORIZONTAL) {
            // Auto control (including a driver-less dragon, which is forced into auto control)
            // lands at the ordered point: near = walk, far = fly, arrive = land.  Manual flight
            // mode (auto control off with a driver) must keep the dragon airborne: it hovers
            // over the destination until the land button is pressed.
            if (effectiveAuto(dragon)) {
                DragonAutopilot.beginLanding(dragon, task);
            }
            return intentTo(dragon, airGoal, DragonMoveMath.HOVER_SPEED, null);
        }
        // Near the goal but not yet above it: the minimum turn radius (cruise speed divided by
        // the yaw rate) can exceed the remaining distance, so the dragon cannot converge by
        // turning at cruise speed and would orbit the goal forever.  Auto control lands now and
        // walks the remaining distance.
        if (effectiveAuto(dragon) && horizontal <= landingApproachRadius(dragon)) {
            DragonAutopilot.beginLanding(dragon, task);
            return intentTo(dragon, airGoal, DragonMoveMath.HOVER_SPEED, null);
        }
        // Manual flight must stay airborne and hover over the goal, so it never lands here;
        // but full cruise speed at this range still exceeds the turn radius.  Decelerate to a
        // speed whose turn circle fits inside the remaining distance so the dragon converges
        // to the hover point instead of circling it forever.
        if (horizontal <= landingApproachRadius(dragon)) {
            double yawRad = Math.toRadians(DragonMoveMath.yawRate(dragon.getDragonStage()));
            double hoverApproachSpeed = Math.max(0.15D, Math.min(0.6D, horizontal * yawRad * 0.5D));
            return intentTo(dragon, airGoal, hoverApproachSpeed, null);
        }
        double speed = DragonMoveMath.CRUISE_SPEED * flightModifier(dragon);
        // Damped altitude guidance: the velocity never points straight at the cruise point.
        // Pure pursuit of that point made the dragon overshoot the climb, then dive back and
        // oscillate down to near-ground height.  Fly level toward the target and add only a
        // small, clamped vertical correction so the altitude converges without oscillation.
        double dx = task.x - dragon.getX();
        double dz = task.z - dragon.getZ();
        double horiz = Math.hypot(dx, dz);
        Vec3 desired = horiz < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D)
                : new Vec3(dx / horiz, clampVertical((y - dragon.getY()) * 0.05D), dz / horiz).normalize();
        Vec3 direct = new Vec3(dx, y - dragon.getY(), dz);
        if (DragonGuidance.directCorridorClear(dragon, direct, state)) {
            if (state.pathJob != null || !state.path.isEmpty()) state.resetNavigation();
            return Intent.velocity(desired.scale(speed), null);
        }
        ensurePath(dragon, state, airGoal);
        // Planning is incremental.  A still-running job is not a failed route and must not
        // advance the emergency counter merely because it needs another server tick.
        if (state.pathJob != null) return Intent.stop();
        if (state.path.isEmpty()) return noPath(dragon, state);
        Vec3 lookAhead = DragonGuidance.lookAhead(dragon, airGoal, state);
        Vec3 toLook = lookAhead.subtract(dragon.position());
        double lookHoriz = Math.hypot(toLook.x, toLook.z);
        Vec3 pathDesired = lookHoriz < 1.0E-6D ? desired
                : new Vec3(toLook.x / lookHoriz, clampVertical((y - dragon.getY()) * 0.05D), toLook.z / lookHoriz).normalize();
        return Intent.velocity(pathDesired.scale(speed), null);
    }

    /** Ground melee for a manual-mode attack order: never auto-takes-off, walks to the target
     *  and lets the native tickMovement melee bite once the bounding boxes overlap. */
    private static void runGroundAttack(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        stopBreath(dragon);
        LivingEntity target = dragon.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved() || target.level() != dragon.level()) {
            DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
            DragonRideState.clearAttackTarget(dragon);
            return;
        }
        Vec3 targetPos = target.position();
        double dx = targetPos.x - dragon.getX();
        double dz = targetPos.z - dragon.getZ();
        double horizontal = Math.hypot(dx, dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        dragon.setYRot((float) yaw);
        dragon.setYHeadRot((float) yaw);
        dragon.yBodyRot = (float) yaw;
        dragon.setXRot(0.0F);
        double meleeRange = Math.max(4.0D, dragon.getBbWidth() * 0.5D + target.getBbWidth() * 0.5D + 1.5D);
        if (horizontal <= meleeRange) {
            // In bite range: stop and face the target; the native melee performs the bite.
            dragon.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 desired = new Vec3(dx / horizontal, 0.0D, dz / horizontal).scale(0.4D);
        dragon.setDeltaMovement(desired.x, 0.0D, desired.z);
    }

    /** Auto control is forced whenever the dragon has no driver (registered rider). */
    private static boolean effectiveAuto(EntityDragonBase dragon) {
        return DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null;
    }

    /** Damped vertical correction: climbs or descends gently, never dives. */
    private static double clampVertical(double value) {
        return Math.max(-0.20D, Math.min(0.20D, value));
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
        if (commander(dragon) == null) {
            // Offline commander: combat fails closed; hover must not continue breathing.
            stopBreath(dragon);
            DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
            return Intent.stop();
        }
        Vec3 targetPos = target.position();
        // Ground combat uses melee: when both the dragon and the target are low, close in and
        // bite instead of hovering and breathing.  The native tickMovement melee performs the
        // actual bite once the bounding boxes overlap.
        double ground = groundHeight(dragon, state);
        boolean lowAltitude = dragon.getY() - ground < 8.0D;
        boolean targetLow = Math.abs(targetPos.y - ground) < 10.0D;
        if (lowAltitude && targetLow) {
            stopBreath(dragon);
            state.hoverAnchor = null;
            Vec3 toTarget = targetPos.subtract(dragon.position());
            double distance = toTarget.length();
            double meleeRange = Math.max(4.0D, dragon.getBbWidth() * 0.5D + target.getBbWidth() * 0.5D + 1.5D);
            if (distance <= meleeRange) {
                // In bite range: face the target and let the native melee bite.
                return intentTo(dragon, targetPos, 0.0D, targetPos);
            }
            Vec3 stopPoint = targetPos.subtract(toTarget.scale(meleeRange / distance));
            return intentTo(dragon, stopPoint, DragonMoveMath.CRUISE_SPEED, targetPos);
        }
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
                targetPos.y + hoverAltitudeFor(dragon), targetPos.z + radial.z * radius);
        fireAt(dragon, state, target, targetPos);
        return intentTo(dragon, state.hoverAnchor, DragonMoveMath.COMBAT_SPEED * flightModifier(dragon), targetPos);
    }

    private static Intent orbit(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        long now = dragon.level().getGameTime();
        double radius = Math.max(1.0D, DragonRideState.aoeRadius(dragon) + 12.0D);
        double halfHeight = Math.max(1.0D, DragonRideState.aoeHalfHeight(dragon));
        if (now - state.lastHostileQueryTick >= HOSTILE_QUERY_INTERVAL) {
            state.lastHostileQueryTick = now;
            // Lock the target: keep firing at the current one while it stays alive, in the
            // cylinder and hostile.  Re-querying "nearest to the dragon" every 10 ticks made the
            // orbiting dragon flip between enemies as it circled.
            LivingEntity current = state.orbitTarget;
            if (current != null && current.isAlive() && !current.isRemoved()
                    && current.level() == dragon.level()
                    && hostileCylinder(center, radius, halfHeight, current)
                    && hostile(dragon, current)) {
                state.orbitNoHostileTicks = 0;
            } else {
                LivingEntity target = nearestHostile(dragon, center);
                state.orbitTarget = target;
                if (target != null) {
                    state.orbitNoHostileTicks = 0;
                } else if (commander(dragon) == null) {
                    // Offline commander: combat fails closed, leave the pattern and land.
                    stopBreath(dragon);
                    DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
                    state.orbitNoHostileTicks = 0;
                } else {
                    state.orbitNoHostileTicks += HOSTILE_QUERY_INTERVAL;
                    if (state.orbitNoHostileTicks >= ORBIT_IDLE_EXIT_TICKS) {
                        stopBreath(dragon);
                        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
                        state.orbitNoHostileTicks = 0;
                    }
                }
            }
        }
        // The breath must pour out every tick at the held target to form a continuous jet
        // stream; firing only on the 10-tick hostile query made the spray choppy and scattered
        // as the orbiting head moved between bursts.
        LivingEntity breathTarget = state.orbitTarget;
        if (breathTarget != null && breathTarget.isAlive() && !breathTarget.isRemoved()
                && breathTarget.level() == dragon.level()) {
            fireAt(dragon, state, breathTarget, breathTarget.position());
        } else {
            state.orbitTarget = null;
        }
        Vec3 radial = new Vec3(dragon.getX() - center.x, 0.0D, dragon.getZ() - center.z);
        radial = DragonMoveMath.normalizeOr(radial, state.lastSafeDirection);
        int sign = (dragon.getUUID().hashCode() & 1) == 0 ? 1 : -1;
        Vec3 tangent = new Vec3(-radial.z * sign, 0.0D, radial.x * sign);
        double radialError = Math.hypot(dragon.getX() - center.x, dragon.getZ() - center.z) - radius;
        double desiredY = center.y + hoverAltitudeFor(dragon);
        Vec3 velocity = tangent.scale(DragonMoveMath.COMBAT_SPEED).add(radial.scale(-radialError * 0.08D))
                .add(0.0D, (desiredY - dragon.getY()) * 0.08D, 0.0D);
        return Intent.velocity(DragonMoveMath.clampSpeed(velocity, DragonMoveMath.COMBAT_SPEED * flightModifier(dragon)), center);
    }

    private static Intent areaHold(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        stopBreath(dragon);
        Vec3 center = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : dragon.position();
        Vec3 anchor = new Vec3(center.x, center.y + hoverAltitudeFor(dragon), center.z);
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
            Vec3 entry = center.add(axis.scale(-(radius + 24.0D))).add(0.0D, hoverAltitudeFor(dragon), 0.0D);
            // Pre-burn during the approach so the first pass over the target already breathes.
            LivingEntity approachTarget = nearestHostile(dragon, center);
            if (approachTarget != null) fireAt(dragon, state, approachTarget, approachTarget.position());
            else emitBreath(dragon, state, center);
            if (dragon.position().distanceToSqr(entry) < 36.0D) {
                state.strafeStage = 1;
                state.strafeStageStartTick = now;
                // Start the run with a fresh burst so the pass is not swallowed by a pause.
                state.breathBurstTicks = 0;
                state.lastFireTick = 0L;
            }
            return intentTo(dragon, entry, DragonMoveMath.STRAFE_SPEED * flightModifier(dragon), center);
        }
        double along = (dragon.getX() - center.x) * axis.x + (dragon.getZ() - center.z) * axis.z;
        if (state.strafeStage == 1) {
            if (Math.abs(along) <= radius + 2.0D) {
                // Breathe over the cylinder; damage is applied directly at the impact point, so
                // the pass deals area damage even without a specifically found hostile.
                LivingEntity target = nearestHostile(dragon, center);
                if (target != null) fireAt(dragon, state, target, target.position());
                else emitBreath(dragon, state, center);
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
        // A takeoff/landing intent can be exactly vertical. Feeding that through the normal
        // yaw/pitch candidate fan clamps +/-90 degrees to +/-35 and silently substitutes the
        // fallback (+Z) heading, making a dragon fly north forever instead of climbing.
        // Keep vertical movement in the same swept-AABB safety system, but do not invent a
        // horizontal heading for it.
        if (Math.hypot(requested.x, requested.z) < 1.0E-4D && Math.abs(requested.y) > 1.0E-4D) {
            Vec3 vertical = new Vec3(0.0D, Math.signum(requested.y), 0.0D);
            // A landing descent must not be stopped by the swept-column check: the landing pad
            // was already validated with a full-AABB no-collision test, and an 8-block lookahead
            // always samples the ground itself once the dragon is close, which used to brake the
            // dragon a few blocks above the pad and strand it hovering.  Minecraft's collision
            // resolution finishes the touchdown and onGround() completes the landing.
            boolean landingDescent = DragonRideState.phase(dragon) == DragonRideState.Phase.LANDING && requested.y < 0.0D;
            if (!DragonGuidance.corridorClear(dragon, vertical, 8.0D) && !landingDescent) {
                applyMotion(dragon, state, DragonMoveMath.normalizeOr(dragon.getDeltaMovement(), state.lastSafeDirection), 0.0D);
            } else {
                applyVerticalMotion(dragon, state, requested.y);
            }
            return;
        }
        Vec3 requestedDir = DragonMoveMath.normalizeOr(requested, state.lastSafeDirection);
        Vec3 safe = DragonGuidance.steer(dragon, requestedDir, state);
        if (safe == null) {
            applyMotion(dragon, state, DragonMoveMath.normalizeOr(dragon.getDeltaMovement(), state.lastSafeDirection), 0.0D);
            return;
        }
        applyMotion(dragon, state, safe, requested.length());
    }

    /** Applies a collision-checked vertical climb/descent without the horizontal fallback used
     * by bounded-pitch steering. */
    private static void applyVerticalMotion(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, double requestedY) {
        // EntityDragonBase applies gravity before its server-AI tail each tick.  Integrating from
        // that already-gravitating value caps a climb at -0.0184 forever, so vertical guidance
        // must supply the requested post-gravity velocity directly.
        state.speed = Math.abs(requestedY);
        dragon.setDeltaMovement(0.0D, requestedY, 0.0D);
        dragon.setXRot(requestedY >= 0.0D ? -35.0F : 35.0F);
    }

    private static void applyMotion(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, Vec3 desiredDirection, double requestedSpeed) {
        Vec3 currentVelocity = dragon.getDeltaMovement();
        double currentSpeed = currentVelocity.length();
        Vec3 currentDirection = DragonMoveMath.normalizeOr(currentVelocity, state.lastSafeDirection);
        double targetSpeed = DragonMoveMath.turnLimitedSpeed(requestedSpeed, currentDirection, desiredDirection);
        // Acceleration is deliberately fast (0.5/tick^2) and braking stays gradual: vanilla flight
        // drag scales the stored velocity by ~0.91 every tick, so a slow acceleration limit made
        // the steady cruise converge well below the requested speed and felt ~3x slower than the
        // native dragon flight.
        double nextSpeed = targetSpeed >= currentSpeed
                ? Math.min(targetSpeed, currentSpeed + 0.5D)
                : DragonMoveMath.approach(currentSpeed, targetSpeed, DragonMoveMath.BRAKE_DECELERATION);
        Vec3 nextDirection = DragonMoveMath.turnToward(currentDirection, desiredDirection, DragonMoveMath.yawRate(dragon.getDragonStage()), DragonMoveMath.PITCH_RATE);
        Vec3 nextVelocity = nextDirection.scale(nextSpeed);
        state.speed = nextSpeed;
        state.lastSafeDirection = nextDirection;
        dragon.setDeltaMovement(nextVelocity);
        double yaw = Math.toDegrees(Math.atan2(-nextDirection.x, nextDirection.z));
        dragon.setYRot((float) yaw);
        dragon.setYHeadRot((float) yaw);
        // Mirror the native flight helper: the body yaw lags one tick behind the nose so the
        // client-side banking logic receives the same inputs as a native dragon and levels out
        // once the heading is straight.
        dragon.yBodyRot = Double.isNaN(state.lastYaw) ? (float) yaw : (float) state.lastYaw;
        state.lastYaw = yaw;
        // Softer visual pitch: the renderer adds dragonPitch on top, so a full velocity pitch
        // here made the body look nose-diving/climbing far beyond the actual trajectory.
        dragon.setXRot((float) DragonMoveMath.clampPitch(DragonMoveMath.pitchTo(nextDirection) * 0.5D));
    }

    private static void fireAt(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, LivingEntity target, Vec3 targetPos) {
        if (!dragon.hasLineOfSight(target)) {
            stopBreath(dragon);
            state.breathBurstTicks = 0;
            return;
        }
        // A directly commanded attack target overrides the faction hostility gate: the player
        // explicitly ordered this target.  Auto-targeted hostiles (orbit/strafe) keep the gate.
        UUID commanded = DragonRideState.attackTarget(dragon);
        boolean commandedTarget = commanded != null && commanded.equals(target.getUUID());
        if (!commandedTarget && !hostile(dragon, target)) {
            stopBreath(dragon);
            state.breathBurstTicks = 0;
            return;
        }
        emitBreath(dragon, state, targetPos);
    }

    /** Sustained breath: particles pour out every tick for BREATH_BURST_TICKS, then the breath
     *  pauses for BREATH_GAP_TICKS before the next burst.  Damage and sound apply on the
     *  FIRE_INTERVAL cadence; particles are emitted every tick for a dense stream. */
    private static void emitBreath(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state, Vec3 targetPos) {
        long now = dragon.level().getGameTime();
        state.breathBurstTicks++;
        if (state.breathBurstTicks > BREATH_BURST_TICKS) {
            state.breathBurstTicks = -BREATH_GAP_TICKS;
            stopBreath(dragon);
            return;
        }
        if (state.breathBurstTicks < 0) return; // pause between bursts
        dragon.setBreathingFire(true);
        spawnBreathParticles(dragon, targetPos);
        if (now - state.lastFireTick >= FIRE_INTERVAL) {
            if (dragon.level() instanceof ServerLevel serverLevel) {
                com.iafenvoy.iceandfire.entity.util.dragon.IafDragonDestructionManager.destroyAreaBreath(
                        serverLevel, net.minecraft.core.BlockPos.containing(targetPos), dragon);
            }
            playBreathSound(dragon);
            state.lastFireTick = now;
        }
    }

    private static void playBreathSound(EntityDragonBase dragon) {
        if (dragon.level().isClientSide()) return;
        // Resolve the native breath sound through Forge's registry: Ice And Fire exposes its
        // sounds through the Architectury DeferredRegister, which is not on our compile path.
        String soundName = "icedragon_breath";
        if (dragon.dragonType == com.iafenvoy.iceandfire.data.DragonType.FIRE) soundName = "firedragon_breath";
        else if (dragon.dragonType == com.iafenvoy.iceandfire.data.DragonType.LIGHTNING) soundName = "lightningdragon_breath";
        net.minecraft.sounds.SoundEvent sound = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(
                new net.minecraft.resources.ResourceLocation("iceandfire", soundName));
        if (sound != null) {
            dragon.level().playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), sound,
                    net.minecraft.sounds.SoundSource.HOSTILE, 4.0F, 1.0F);
        }
    }

    /** Restores the native dragon roar and wing-flap sounds for controlled dragons.  Both native
     *  paths are suppressed under Dominion control (the ambient/AI step and the server logic are
     *  cancelled), so the sounds are re-emitted here on the server with the native cadence; the
     *  camera-sound bridge then forwards them to command cameras. */
    private static void dragonSounds(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        long now = dragon.level().getGameTime();
        // Wing flap: native cadence is one play per ~30 ticks while airborne.
        if ((dragon.isFlying() || dragon.isHovering()) && now - state.lastFlapSoundTick >= 30L) {
            state.lastFlapSoundTick = now;
            net.minecraft.sounds.SoundEvent flap = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(
                    new net.minecraft.resources.ResourceLocation("iceandfire", "dragon_flight"));
            if (flap != null) dragon.playSound(flap, 16.0F, 1.0F);
        }
        // Ambient roar: occasionally, roughly every 25 seconds, like the native idle roar.
        if (now - state.lastRoarSoundTick >= 300L) {
            state.lastRoarSoundTick = now;
            if (dragon.getRandom().nextInt(5) == 0) {
                float volume = 6.0F + Math.max(0, dragon.getDragonStage() - 2);
                dragon.playSound(dragon.getRoarSound(), volume, 0.8F);
            }
        }
    }

    /** Flying breath spray: particles launched from the mouth toward the target at a visible
     *  speed so the spray process is clearly visible.  ParticleDragonFrost/Flame randomize the
     *  velocity by +-50% per axis (IAF's own RandomHelper), which gives the native wide-spray
     *  look; the direction cone is kept tight.  overrideLimiter=true renders at any distance. */
    private static void spawnBreathParticles(EntityDragonBase dragon, Vec3 targetPos) {
        if (!(dragon.level() instanceof ServerLevel serverLevel)) return;
        net.minecraft.core.particles.ParticleOptions type = breathParticleType(dragon);
        if (type == null) return;
        Vec3 head = dragon.getHeadPosition();
        Vec3 delta = targetPos.subtract(head);
        double distance = delta.length();
        if (distance < 1.0E-4D) return;
        Vec3 direction = delta.scale(1.0D / distance);
        java.util.List<ServerPlayer> players = serverLevel.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player.level() == serverLevel && player.distanceToSqr(dragon) <= 16384.0D).toList();
        if (players.isEmpty()) return;
        net.minecraft.util.RandomSource random = dragon.getRandom();
        // Three exact-velocity stream packets plus one batched impact packet preserve the jet
        // shape without multiplying 18 packets by every player on every breath tick.
        double speed = Math.min(4.0D, distance * 0.24D);
        for (int i = 0; i < 3; i++) {
            Vec3 dir = new Vec3(
                    direction.x + (random.nextDouble() - 0.5D) * 0.05D,
                    direction.y + (random.nextDouble() - 0.5D) * 0.05D,
                    direction.z + (random.nextDouble() - 0.5D) * 0.05D).normalize();
            Vec3 velocity = dir.scale(speed);
            net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet =
                    new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(type, false,
                            head.x, head.y, head.z,
                            (float) velocity.x, (float) velocity.y, (float) velocity.z, 1.0F, 0);
            for (ServerPlayer player : players) player.connection.send(packet);
        }
        net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket impact =
                new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(type, false,
                        targetPos.x, targetPos.y, targetPos.z, 0.7F, 0.7F, 0.7F, 0.15F, 6);
        for (ServerPlayer player : players) player.connection.send(impact);
    }
    private static net.minecraft.core.particles.ParticleOptions breathParticleType(EntityDragonBase dragon) {
        float scale = (float) Math.min(dragon.getRenderSize() * 0.35D, 7.0D);
        if (dragon.dragonType == com.iafenvoy.iceandfire.data.DragonType.FIRE) {
            return new com.iafenvoy.iceandfire.particle.DragonFlameParticleType(scale);
        }
        if (dragon.dragonType == com.iafenvoy.iceandfire.data.DragonType.ICE) {
            return new com.iafenvoy.iceandfire.particle.DragonFrostParticleType(scale);
        }
        return null;
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
            // Rank by distance to the AOE center, not to the orbiting dragon, so the chosen
            // target stays stable while the dragon circles.
            double distance = candidate.distanceToSqr(center);
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
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        return player != null && player.level() == dragon.level() && DragonControlPolicy.allows(player, dragon)
                ? player : null;
    }

    private static void finishLanding(EntityDragonBase dragon) {
        stopBreath(dragon);
        dragon.setFlying(false);
        dragon.setHovering(false);
        DragonRideState.clearLandingSpot(dragon);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        reset(dragon);
        // The task is intentionally kept.  Dominion Sword re-pulses the same offline task every
        // server tick until its own settle logic stops it; clearing the task here created an
        // endless set/clear loop that made the dragon re-arm takeoff/landing every tick.
    }

    private static boolean finite(Vec3 value) { return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }

    /** Size-scaled hover altitude above the ground/target: ~8 blocks for a baby dragon,
     *  rising smoothly to the full 24 for a maxed elder (renderSize 30). */
    private static double hoverAltitudeFor(EntityDragonBase dragon) {
        double renderSize = Math.max(1.0F, dragon.getRenderSize());
        return 8.0D + renderSize * 0.5333333333333333D;
    }

    private static double flightModifier(EntityDragonBase dragon) {
        // Native IAF air speed scales with feeding/growth: movementSpeed * (5.2 + 1.5 * age
        // fraction), where the movement attribute grows from minimumSpeed to maximumSpeed over
        // the first 125 days.  Scale our base mode speeds by the dragon's native air speed
        // relative to a fully grown elder, so small dragons fly slowly and maxed dragons reach
        // the native speed.  The config flight-speed modifier still applies on top.
        double speed = dragon.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        double min = dragon.minimumSpeed;
        double max = dragon.maximumSpeed;
        double fraction = max > min ? Math.max(0.0D, Math.min(1.0D, (speed - min) / (max - min))) : 1.0D;
        double elderNative = Math.max(0.01D, max) * 6.7D;
        double sizeScale = Math.max(0.0D, speed * (5.2D + 1.5D * fraction)) / elderNative;
        double config = dragon.getFlightSpeedModifier();
        if (!Double.isFinite(config)) config = 1.0D;
        config = Math.max(0.25D, Math.min(2.0D, config));
        return Math.max(0.15D, Math.min(2.5D, sizeScale * config));
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

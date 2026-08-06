package com.arxyt.dominionsword.iceandfirecompat.control;

import com.arxyt.dominionsword.control.PlayerControl;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Per-tick driver for a Dominion-controlled dragon.
 *
 * <p>The dragon keeps its native navigators and flight manager; this class only feeds goals and
 * transitions them between ground, takeoff, cruise and landing. Online dragons are driven exactly
 * once per tick from {@link #tick(MinecraftServer)}; offline persistent tasks are driven through
 * the adapter's {@code move(null, ...)} pulse.
 */
public final class DragonAutopilot {
    private static final double ARRIVAL_HORIZONTAL = 3.5D;
    private static final double ARRIVAL_VERTICAL = 2.5D;
    private static final double LANDING_HOLD_RADIUS = 5.0D;
    private static final double LANDING_HOLD_ALTITUDE = 8.0D;
    private static final int PATH_RETRY_BASE_TICKS = 10;
    private static final int PATH_RETRY_MAX_TICKS = 40;
    private static final int PATH_MAX_FAILURES = 3;

    private DragonAutopilot() {
    }

    /** Single online driver: steps every dragon in an online player's selection. */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (UUID id : PlayerControl.ids(player)) {
                Entity entity = player.serverLevel().getEntity(id);
                if (!(entity instanceof EntityDragonBase dragon)) continue;
                if (!PlayerControl.isControlled(entity) || !player.getUUID().equals(PlayerControl.controller(entity))) continue;
                if (!DragonRideState.isControlled(dragon)) continue;
                if (!DragonControlPolicy.allows(player, dragon) || dragon.isRemoved() || !dragon.isAlive() || dragon.isModelDead()) {
                    endControl(dragon);
                    continue;
                }
                refreshRider(dragon);
                if (DragonRideState.hasGoal(dragon)) stepMove(dragon, DragonRideState.goal(dragon));
                else hold(dragon);
            }
        }
    }

    public static void beginControl(EntityDragonBase dragon) {
        if (dragon == null) return;
        DragonRideState.setPrevCommand(dragon, dragon.getCommand());
        DragonRideState.setControlled(dragon, true);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        wake(dragon);
    }

    /**
     * Ends Dominion control. When Dominion Sword keeps a persistent offline task (for example a
     * player logout with a preserved move order), the dragon stays controlled so navigation and
     * landing can finish without an online commander.
     */
    public static void endControl(EntityDragonBase dragon) {
        if (dragon == null || !DragonRideState.isControlled(dragon)) return;
        boolean persistentTask = PlayerControl.hasPersistentVehicleTask(dragon);
        UUID commanded = DragonRideState.attackTarget(dragon);
        if (commanded != null && dragon.getTarget() != null && commanded.equals(dragon.getTarget().getUUID())) {
            dragon.setTarget(null);
        }
        if (persistentTask) {
            wake(dragon);
            return;
        }
        boolean hasRider = DragonRideState.riderId(dragon) != null;
        DragonRideState.clearControlState(dragon, hasRider, false);
        dragon.setCommand(hasRider ? 0 : DragonRideState.prevCommand(dragon));
        DragonRideState.setControlled(dragon, false);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        if (dragon.isHovering() || dragon.isFlying()) {
            dragon.setHovering(true);
            dragon.setFlying(false);
        }
    }

    /**
     * Goal-setter used by the vehicle adapter. Only a materially changed goal resets the phase,
     * stops the old path and clears landing/backoff state; repeated pulses are no-ops.
     *
     * @return true when the goal actually changed
     */
    public static boolean updateGoal(EntityDragonBase dragon, Vec3 target) {
        if (dragon == null || target == null) return false;
        Vec3 current = DragonRideState.hasGoal(dragon) ? DragonRideState.goal(dragon) : null;
        if (!DragonMoveMath.goalChanged(current, target)) return false;
        DragonRideState.setGoal(dragon, target);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonRideState.clearLandingSpot(dragon);
        DragonRideState.clearLandingCooldown(dragon);
        DragonRideState.clearPathBackoff(dragon);
        wake(dragon);
        if (dragon.getNavigation() != null) dragon.getNavigation().stop();
        return true;
    }

    /** One navigation step. Offline continuation calls this from the adapter's {@code move(null, ...)} pulse. */
    public static void stepMove(EntityDragonBase dragon, Vec3 goal) {
        if (dragon == null || goal == null || dragon.isRemoved() || !dragon.isAlive() || dragon.isModelDead()) return;
        wake(dragon);
        boolean mounted = DragonRideState.riderId(dragon) != null;
        boolean auto = DragonRideState.autoControl(dragon) || !mounted;
        double horizontal = Math.hypot(goal.x - dragon.getX(), goal.z - dragon.getZ());
        double vertical = goal.y - dragon.getY();
        DragonRideState.Phase phase = DragonRideState.phase(dragon);
        if (dragon.isFlying() || dragon.isHovering()) {
            switch (DragonMoveMath.decideAirborne(horizontal, auto, phase)) {
                case TAKEOFF -> takeoffTick(dragon);
                case LANDING -> landingTick(dragon);
                case LAND -> beginLanding(dragon, goal);
                default -> cruiseTick(dragon, goal);
            }
            return;
        }
        switch (DragonMoveMath.decideGrounded(horizontal, vertical, auto, phase, dragon.onGround())) {
            case LANDING -> {
                landingTick(dragon);
                return;
            }
            case TAKEOFF -> {
                beginTakeoff(dragon);
                return;
            }
            default -> {
                // continue with ground movement below
            }
        }
        if (phase == DragonRideState.Phase.TAKEOFF || phase == DragonRideState.Phase.LANDING) {
            endFlight(dragon);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        }
        groundMove(dragon, goal);
        if (arrived(dragon, goal)) {
            DragonRideState.clearGoal(dragon);
            DragonRideState.clearPathBackoff(dragon);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        }
    }

    public static void beginTakeoff(EntityDragonBase dragon) {
        if (dragon == null || !DragonRideState.isControlled(dragon) || dragon.isModelDead() || !dragon.isAlive()) return;
        wake(dragon);
        if (!dragon.hasFlightClearance()) return;
        DragonRideState.setPhase(dragon, DragonRideState.Phase.TAKEOFF);
        dragon.setHovering(true);
        dragon.setFlying(false);
        dragon.flightManager.setFlightTarget(liftTarget(dragon));
    }

    public static void beginLanding(EntityDragonBase dragon, Vec3 center) {
        if (dragon == null || !DragonRideState.isControlled(dragon) || dragon.isModelDead() || !dragon.isAlive()) return;
        long now = dragon.level().getGameTime();
        if (now < DragonRideState.landingCooldownUntil(dragon)) {
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
            return;
        }
        Vec3 spot = DragonLandingPlanner.findLandingSpot(dragon, center == null ? dragon.position() : center);
        if (spot == null) {
            DragonRideState.setLandingCooldown(dragon, now + DragonLandingPlanner.COOLDOWN_TICKS);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
            return;
        }
        wake(dragon);
        DragonRideState.setLandingSpot(dragon, spot);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.LANDING);
        if (!dragon.isFlying() && !dragon.isHovering()) {
            dragon.setHovering(true);
            dragon.setFlying(false);
        }
    }

    /** Wakes the dragon and pins its native command to stand while controlled. */
    public static void wake(EntityDragonBase dragon) {
        if (dragon == null) return;
        dragon.setCommand(0);
        dragon.setInSittingPose(false);
        dragon.setOrderedToSit(false);
    }

    private static void takeoffTick(EntityDragonBase dragon) {
        dragon.flightManager.setFlightTarget(liftTarget(dragon));
        if (!dragon.isHovering() && !dragon.isFlying()) dragon.setHovering(true);
        if (dragon.isFlying()) DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
    }

    private static void cruiseTick(EntityDragonBase dragon, Vec3 goal) {
        double altitude = DragonMoveMath.resolveCruiseAltitude(goal.y, dragon.getY(),
                dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight());
        dragon.flightManager.setFlightTarget(new Vec3(goal.x, altitude, goal.z));
        if (!dragon.isFlying() && !dragon.isHovering()) {
            dragon.setHovering(true);
            dragon.setFlying(false);
        }
        DragonRideState.setPhase(dragon, DragonRideState.Phase.CRUISE);
    }

    private static void landingTick(EntityDragonBase dragon) {
        Vec3 spot = DragonRideState.hasLandingSpot(dragon) ? DragonRideState.landingSpot(dragon) : null;
        if (spot == null) {
            endFlight(dragon);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
            return;
        }
        double horizontal = Math.hypot(spot.x - dragon.getX(), spot.z - dragon.getZ());
        if (horizontal > LANDING_HOLD_RADIUS || dragon.getY() > spot.y + LANDING_HOLD_ALTITUDE + 2.0D) {
            dragon.down(false);
            dragon.setHovering(true);
            dragon.setFlying(false);
            dragon.flightManager.setFlightTarget(new Vec3(spot.x, Math.max(spot.y + LANDING_HOLD_ALTITUDE, dragon.getY() - 1.0D), spot.z));
            return;
        }
        dragon.down(true);
        dragon.setHovering(true);
        dragon.setFlying(false);
        if (dragon.onGround()) {
            dragon.down(false);
            endFlight(dragon);
            DragonRideState.clearLandingSpot(dragon);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
            if (DragonRideState.hasGoal(dragon)) {
                Vec3 goal = DragonRideState.goal(dragon);
                if (arrived(dragon, goal)) {
                    DragonRideState.clearGoal(dragon);
                    DragonRideState.clearPathBackoff(dragon);
                }
            }
        }
    }

    private static void groundMove(EntityDragonBase dragon, Vec3 goal) {
        long now = dragon.level().getGameTime();
        if (dragon.getNavigation().isInProgress()) return;
        int fails = DragonRideState.pathFailCount(dragon);
        long lastAttempt = DragonRideState.pathAttemptTick(dragon);
        if (lastAttempt > 0L && now - lastAttempt < Math.min(PATH_RETRY_MAX_TICKS, PATH_RETRY_BASE_TICKS * (fails + 1))) {
            return;
        }
        DragonRideState.setPathAttemptTick(dragon, now);
        boolean created = dragon.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
        if (created) {
            DragonRideState.setPathFailCount(dragon, 0);
            return;
        }
        int newFails = fails + 1;
        DragonRideState.setPathFailCount(dragon, newFails);
        if (newFails >= PATH_MAX_FAILURES) {
            if (dragon.hasFlightClearance()) {
                beginTakeoff(dragon);
            } else {
                DragonRideState.clearGoal(dragon);
                DragonRideState.clearPathBackoff(dragon);
                DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
            }
        }
    }

    private static void hold(EntityDragonBase dragon) {
        if (dragon.isFlying() || dragon.isHovering()) return;
        if (dragon.getNavigation() != null && !dragon.getNavigation().isDone()) dragon.getNavigation().stop();
        if (dragon.onGround()) {
            dragon.setHovering(false);
            dragon.setFlying(false);
        }
    }

    private static void endFlight(EntityDragonBase dragon) {
        dragon.down(false);
        dragon.setHovering(false);
        dragon.setFlying(false);
    }

    private static Vec3 liftTarget(EntityDragonBase dragon) {
        double y = DragonMoveMath.resolveLiftAltitude(dragon.getY(),
                dragon.level().getMinBuildHeight(), dragon.level().getMaxBuildHeight());
        return new Vec3(dragon.getX(), y, dragon.getZ());
    }

    private static boolean arrived(EntityDragonBase dragon, Vec3 goal) {
        double horizontal = Math.hypot(goal.x - dragon.getX(), goal.z - dragon.getZ());
        double vertical = Math.abs(goal.y - dragon.getY());
        return horizontal <= ARRIVAL_HORIZONTAL && vertical <= ARRIVAL_VERTICAL;
    }

    private static void refreshRider(EntityDragonBase dragon) {
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId == null) return;
        Mob rider = DragonRideState.riderEntity(dragon);
        if (rider == null || !rider.isAlive() || rider.getVehicle() != dragon) {
            DragonRideState.clearRiderMarkers(dragon, rider);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        }
    }
}

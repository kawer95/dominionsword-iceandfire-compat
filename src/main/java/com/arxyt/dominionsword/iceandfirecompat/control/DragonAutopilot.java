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
 * Per-tick driver for a Dominion-controlled dragon. The dragon keeps its native
 * navigators and flight manager; this class only feeds goals and transitions them
 * between ground, takeoff, cruise and landing.
 */
public final class DragonAutopilot {
    public static final double AUTO_TAKEOFF_DISTANCE = 48.0D;
    public static final double AUTO_LAND_DISTANCE = 24.0D;
    public static final double CRUISE_ALTITUDE = 16.0D;
    public static final double MAX_FLIGHT_ALTITUDE = 128.0D;
    private static final double TAKEOFF_LIFT = 12.0D;
    private static final double VERTICAL_TAKEOFF_THRESHOLD = 10.0D;
    private static final double ARRIVAL_HORIZONTAL = 3.5D;
    private static final double ARRIVAL_VERTICAL = 2.5D;
    private static final double LANDING_HOLD_RADIUS = 5.0D;
    private static final double LANDING_HOLD_ALTITUDE = 8.0D;

    private DragonAutopilot() {
    }

    /** Steps every dragon in an online player's selection. Offline persistent tasks are driven through the adapter. */
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
        DragonRideState.clearAttackTarget(dragon);
        DragonRideState.clearGoal(dragon);
        DragonRideState.clearLandingSpot(dragon);
        boolean hasRider = DragonRideState.riderId(dragon) != null;
        dragon.setCommand(hasRider ? 0 : DragonRideState.prevCommand(dragon));
        DragonRideState.setControlled(dragon, false);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        if (dragon.isHovering() || dragon.isFlying()) {
            dragon.setHovering(true);
            dragon.setFlying(false);
        }
    }

    /** One navigation step. The adapter calls this for every move order, including offline continuation. */
    public static void stepMove(EntityDragonBase dragon, Vec3 goal) {
        if (dragon == null || goal == null || dragon.isRemoved() || !dragon.isAlive() || dragon.isModelDead()) return;
        wake(dragon);
        boolean mounted = DragonRideState.riderId(dragon) != null;
        boolean auto = DragonRideState.autoControl(dragon) || !mounted;
        double horizontal = Math.hypot(goal.x - dragon.getX(), goal.z - dragon.getZ());
        double vertical = goal.y - dragon.getY();
        DragonRideState.Phase phase = DragonRideState.phase(dragon);
        if (dragon.isFlying() || dragon.isHovering()) {
            if (phase == DragonRideState.Phase.TAKEOFF) {
                takeoffTick(dragon);
                return;
            }
            if (phase == DragonRideState.Phase.LANDING) {
                landingTick(dragon);
                return;
            }
            if (auto && horizontal <= AUTO_LAND_DISTANCE) {
                beginLanding(dragon, goal);
                return;
            }
            cruiseTick(dragon, goal);
            return;
        }
        if (phase == DragonRideState.Phase.LANDING && !dragon.onGround()) {
            landingTick(dragon);
            return;
        }
        if (phase == DragonRideState.Phase.TAKEOFF || phase == DragonRideState.Phase.LANDING) {
            endFlight(dragon);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        }
        if (auto && (horizontal >= AUTO_TAKEOFF_DISTANCE || vertical > VERTICAL_TAKEOFF_THRESHOLD)) {
            beginTakeoff(dragon);
            return;
        }
        groundMove(dragon, goal);
        if (arrived(dragon, goal)) {
            DragonRideState.clearGoal(dragon);
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
        Vec3 spot = DragonLandingPlanner.findLandingSpot(dragon, center == null ? dragon.position() : center);
        if (spot == null) {
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
        double surface = Math.max(goal.y, dragon.getY() - 2.0D);
        double altitude = Math.min(MAX_FLIGHT_ALTITUDE, Math.max(8.0D, surface + CRUISE_ALTITUDE));
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
                if (arrived(dragon, goal)) DragonRideState.clearGoal(dragon);
            }
        }
    }

    private static void groundMove(EntityDragonBase dragon, Vec3 goal) {
        if (dragon.getNavigation().isDone()) {
            dragon.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
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
        return new Vec3(dragon.getX(), Math.min(MAX_FLIGHT_ALTITUDE, dragon.getY() + TAKEOFF_LIFT), dragon.getZ());
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

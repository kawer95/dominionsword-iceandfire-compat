package com.arxyt.dominionsword.iceandfirecompat.control;

import com.arxyt.dominionsword.control.PlayerControl;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Mission and state maintenance for Dominion-controlled dragons. Airborne motion is fully owned by
 * {@link DragonFlightController}; this class handles selection maintenance, task changes and
 * takeoff/landing initiation.
 */
public final class DragonAutopilot {
    private static final int RIDER_MISMATCH_GRACE_TICKS = 20;
    private DragonAutopilot() {
    }

    /** Online maintenance only: ownership checks, rider marker refresh and stale mission cleanup. */
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
                healRiderMarker(dragon);
            }
        }
    }

    /**
     * Repairs a lost rider marker.  The marker is the rider-protection key: if it is missing
     * while a controlled dragon has exactly one mounted mob, the marker is restored so the
     * native prey-in-mouth pipeline can never bite the rider again.
     */
    private static void healRiderMarker(EntityDragonBase dragon) {
        if (dragon == null || DragonRideState.riderId(dragon) != null) return;
        if (!(dragon.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        java.util.List<Entity> passengers = dragon.getPassengers();
        if (passengers.size() != 1) return;
        Entity passenger = passengers.get(0);
        if (!(passenger instanceof Mob mob)) return;
        DragonRideState.setRiderId(dragon, mob.getUUID());
        DragonRideState.setRiderDragon(mob, dragon.getUUID());
    }

    /** Runs from the tail of the dragon's own server-AI tick, after Ice and Fire has finished
     * mutating flight flags and movement state for this tick. */
    public static void tickFlight(EntityDragonBase dragon) {
        if (dragon == null || !DragonRideState.isControlled(dragon)) return;
        DragonFlightController.tick(dragon);
    }

    public static void beginControl(EntityDragonBase dragon) {
        if (dragon == null) return;
        if (DragonRideState.isControlled(dragon)) {
            if (!DragonRideState.hasTask(dragon)) holdGround(dragon);
            return;
        }
        DragonRideState.setPrevCommand(dragon, dragon.getCommand());
        // A fresh selection is not permission to resume an old NBT movement/attack order.
        // Keep only the main mod's explicit offline task; every ordinary old flight target must
        // be discarded before the controller can decide to take off.
        if (!PlayerControl.hasPersistentVehicleTask(dragon)) {
            DragonRideState.clearTask(dragon);
            DragonRideState.clearAttackTarget(dragon);
            DragonRideState.clearLandingSpot(dragon);
            DragonRideState.clearLandingCooldown(dragon);
            DragonFlightController.reset(dragon);
        }
        DragonRideState.setControlled(dragon, true);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        // Selecting an already-airborne dragon must never drop it to the ground: keep it
        // cruising.  Only a grounded dragon is held in place.
        boolean airborne = dragon.isFlying() || dragon.isHovering();
        DragonRideState.setPhase(dragon, airborne ? DragonRideState.Phase.CRUISE : DragonRideState.Phase.GROUND);
        if (airborne) {
            dragon.setFlying(true);
            dragon.setHovering(false);
        } else {
            holdGround(dragon);
        }
        // Keep the pre-selection Ice and Fire command (often "sit") until a real Dominion
        // order arrives. Calling wake() here changes command 1 to command 0, which makes the
        // native follow AI choose an unrelated destination and take off immediately.
    }

    /**
     * Ends Dominion control. When Dominion Sword keeps a persistent offline task the dragon stays
     * controlled so navigation and landing can finish without an online commander.
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
        DragonRideState.clearControlState(dragon, false);
        dragon.setCommand(hasRider ? 0 : DragonRideState.prevCommand(dragon));
        DragonRideState.setControlled(dragon, false);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        if (dragon.isHovering() || dragon.isFlying()) {
            dragon.setHovering(true);
            dragon.setFlying(false);
        }
    }

    /** Non-recoverable cleanup path for death, explicit release, owner changes and invalid permissions. */
    public static void forceEndControl(EntityDragonBase dragon) {
        if (dragon == null) return;
        DragonFlightController.reset(dragon);
        if (dragon.getTarget() != null) dragon.setTarget(null);
        // Deliberately keep the rider marker pair: a still-mounted rider must stay protected
        // from the native prey-in-mouth pipeline even while the dragon is no longer controlled.
        DragonRideState.clearControlState(dragon, false);
        DragonRideState.setControlled(dragon, false);
        dragon.setFlying(false);
        dragon.setHovering(false);
        dragon.setCommand(DragonRideState.prevCommand(dragon));
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
    }

    /**
     * Task setter used by the vehicle adapter. Only a materially changed task resets mission state;
     * repeated pulses are no-ops and never cancel an active maneuver.
     */
    public static boolean updateTask(EntityDragonBase dragon, Vec3 target) {
        if (dragon == null || target == null) return false;
        Vec3 current = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : null;
        boolean changed = DragonMoveMath.goalChanged(current, target);
        if (!changed) return false;
        DragonRideState.setTask(dragon, target);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        // A new command while the dragon is airborne must redirect in the air, not slam it to
        // the ground.  Ground phase is only used when the dragon is actually on the ground.
        boolean airborne = DragonRideState.phase(dragon) != DragonRideState.Phase.GROUND
                || dragon.isFlying() || dragon.isHovering();
        DragonRideState.setPhase(dragon, airborne ? DragonRideState.Phase.CRUISE : DragonRideState.Phase.GROUND);
        // A task pulse is a movement command; make sure the controller is armed even if a
        // previous lifecycle path dropped the controlled marker (offline persistent tasks).
        DragonRideState.setControlled(dragon, true);
        DragonRideState.clearLandingSpot(dragon);
        DragonRideState.clearLandingCooldown(dragon);
        DragonRideState.clearPathBackoff(dragon);
        wake(dragon);
        return true;
    }

    public static void beginTakeoff(EntityDragonBase dragon) {
        if (dragon == null || !DragonRideState.isControlled(dragon) || dragon.isModelDead() || !dragon.isAlive()) return;
        wake(dragon);
        if (!dragon.hasFlightClearance()) return;
        DragonRideState.setPhase(dragon, DragonRideState.Phase.TAKEOFF);
        dragon.setFlying(true);
        dragon.setHovering(false);
        DragonFlightRegistry.state(dragon).takeoffStartY = dragon.getY();
    }

    public static void beginLanding(EntityDragonBase dragon, Vec3 center) {
        if (dragon == null || !DragonRideState.isControlled(dragon) || dragon.isModelDead() || !dragon.isAlive()) return;
        long now = dragon.level().getGameTime();
        if (now < DragonRideState.landingCooldownUntil(dragon)) return;
        Vec3 request = center == null ? dragon.position() : center;
        Vec3 spot = DragonLandingPlanner.findLandingSpot(dragon, request);
        if (spot == null) {
            // Fallback: descend onto the motion-blocking surface under the requested point.
            // Without this, a failed pad search left the dragon circling at cruise altitude while
            // the cooldown deferred the landing attempt forever.
            int surfaceY = dragon.level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(request.x), (int) Math.floor(request.z));
            int minBuild = dragon.level().getMinBuildHeight();
            int maxBuild = dragon.level().getMaxBuildHeight();
            if (surfaceY <= minBuild || surfaceY >= maxBuild) {
                surfaceY = Math.max(minBuild + 1, (int) Math.floor(dragon.getY()) - 1);
            }
            spot = new Vec3(request.x, surfaceY, request.z);
        }
        wake(dragon);
        DragonRideState.setLandingSpot(dragon, spot);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.LANDING);
        dragon.setFlying(true);
        dragon.setHovering(false);
    }

    /**
     * Wakes the dragon without changing its native command.  Ice and Fire command 0 means
     * "idle wander" and command 2 means "escort flight"; both make the native logic take off or
     * pick a random destination.  Dominion owns movement, so the pre-selection command (usually
     * 1 = sit) is deliberately preserved.
     */
    public static void wake(EntityDragonBase dragon) {
        if (dragon == null) return;
        dragon.setInSittingPose(false);
        dragon.setOrderedToSit(false);
    }

    /** A selected dragon with no Dominion task must not be handed back to Ice and Fire wandering AI. */
    public static void holdGround(EntityDragonBase dragon) {
        if (dragon == null) return;
        dragon.getNavigation().stop();
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.setFlying(false);
        dragon.setHovering(false);
    }

    private static void refreshRider(EntityDragonBase dragon) {
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId == null) return;
        Mob rider = DragonRideState.riderEntity(dragon);
        if (rider == null) {
            // The rider is not in the level's entity lookup yet (world load or chunk edge).
            // Clearing the marker here races the board sequence and hands a still-mounted rider
            // back to the native prey pipeline, so keep the marker until the rider is found.
            return;
        }
        DragonFlightRegistry.RuntimeState runtime = DragonFlightRegistry.state(dragon);
        if (!rider.isAlive() || rider.isRemoved()) {
            DragonRideState.clearRiderMarkers(dragon, rider);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
            runtime.riderMismatchTicks = 0;
            return;
        }
        if (rider.getVehicle() == dragon && dragon.getPassengers().contains(rider)) {
            runtime.riderMismatchTicks = 0;
            return;
        }
        // Boarding and entity reload briefly expose an unresolved passenger relation.  Keep a
        // short grace period, then remove both markers so a forced/external dismount cannot leave
        // stale render state or permanent protection from this dragon.
        if (++runtime.riderMismatchTicks >= RIDER_MISMATCH_GRACE_TICKS) {
            DragonRideState.clearRiderMarkers(dragon, rider);
            DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
            runtime.riderMismatchTicks = 0;
        }
    }
}

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
 * Mission and state maintenance for Dominion-controlled dragons. Airborne motion is fully owned by
 * {@link DragonFlightController}; this class handles selection maintenance, task changes and
 * takeoff/landing initiation.
 */
public final class DragonAutopilot {
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
            }
        }
    }

    public static void beginControl(EntityDragonBase dragon) {
        if (dragon == null) return;
        DragonRideState.setPrevCommand(dragon, dragon.getCommand());
        DragonRideState.setControlled(dragon, true);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        wake(dragon);
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
     * Task setter used by the vehicle adapter. Only a materially changed task resets mission state;
     * repeated pulses are no-ops and never cancel an active maneuver.
     */
    public static boolean updateTask(EntityDragonBase dragon, Vec3 target) {
        if (dragon == null || target == null) return false;
        Vec3 current = DragonRideState.hasTask(dragon) ? DragonRideState.task(dragon) : null;
        if (!DragonMoveMath.goalChanged(current, target)) return false;
        DragonRideState.setTask(dragon, target);
        DragonRideState.setMission(dragon, DragonRideState.Mission.TRANSIT);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonRideState.clearLandingSpot(dragon);
        DragonRideState.clearLandingCooldown(dragon);
        DragonRideState.clearPathBackoff(dragon);
        wake(dragon);
        if (dragon.onGround() && !dragon.isFlying() && dragon.hasFlightClearance()) {
            beginTakeoff(dragon);
        }
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
        Vec3 spot = DragonLandingPlanner.findLandingSpot(dragon, center == null ? dragon.position() : center);
        if (spot == null) {
            DragonRideState.setLandingCooldown(dragon, now + DragonLandingPlanner.COOLDOWN_TICKS);
            return;
        }
        wake(dragon);
        DragonRideState.setLandingSpot(dragon, spot);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.LANDING);
        dragon.setFlying(true);
        dragon.setHovering(false);
    }

    /** Wakes the dragon and pins its native command to stand while controlled. */
    public static void wake(EntityDragonBase dragon) {
        if (dragon == null) return;
        dragon.setCommand(0);
        dragon.setInSittingPose(false);
        dragon.setOrderedToSit(false);
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

package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Persistent Dominion-controlled dragon state. Everything is stored on the dragon (and the
 * designated rider) so chunk unload and server restarts restore the same ride.
 */
public final class DragonRideState {
    public static final String CONTROLLED = "DominionIafControlled";
    public static final String RIDER = "DominionIafRider";
    public static final String RIDER_DRAGON = "DominionIafRiderDragon";
    public static final String AUTO_CONTROL = "DominionIafAutoControl";
    public static final String PHASE = "DominionIafPhase";
    public static final String GOAL_X = "DominionIafGoalX";
    public static final String GOAL_Y = "DominionIafGoalY";
    public static final String GOAL_Z = "DominionIafGoalZ";
    public static final String LANDING_X = "DominionIafLandingX";
    public static final String LANDING_Y = "DominionIafLandingY";
    public static final String LANDING_Z = "DominionIafLandingZ";
    public static final String ATTACK_TARGET = "DominionIafAttackTarget";
    public static final String PREV_COMMAND = "DominionIafPrevCommand";

    public enum Phase {
        GROUND,
        TAKEOFF,
        CRUISE,
        LANDING
    }

    private DragonRideState() {
    }

    private static CompoundTag tag(Entity entity) {
        return entity.getPersistentData();
    }

    public static boolean isControlled(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).getBoolean(CONTROLLED);
    }

    public static void setControlled(EntityDragonBase dragon, boolean controlled) {
        if (dragon != null) tag(dragon).putBoolean(CONTROLLED, controlled);
    }

    public static UUID riderId(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).hasUUID(RIDER) ? tag(dragon).getUUID(RIDER) : null;
    }

    public static void setRiderId(EntityDragonBase dragon, UUID rider) {
        if (dragon != null && rider != null) tag(dragon).putUUID(RIDER, rider);
    }

    public static Mob riderEntity(EntityDragonBase dragon) {
        UUID id = riderId(dragon);
        if (id == null || dragon == null || !(dragon.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(id);
        return entity instanceof Mob mob ? mob : null;
    }

    public static void setRiderDragon(Mob rider, UUID dragonId) {
        if (rider != null && dragonId != null) tag(rider).putUUID(RIDER_DRAGON, dragonId);
    }

    public static void clearRiderMarkers(EntityDragonBase dragon, Mob rider) {
        if (dragon != null) tag(dragon).remove(RIDER);
        if (rider != null) tag(rider).remove(RIDER_DRAGON);
    }

    public static boolean autoControl(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).getBoolean(AUTO_CONTROL);
    }

    public static void setAutoControl(EntityDragonBase dragon, boolean auto) {
        if (dragon != null) tag(dragon).putBoolean(AUTO_CONTROL, auto);
    }

    public static Phase phase(EntityDragonBase dragon) {
        if (dragon == null) return Phase.GROUND;
        String name = tag(dragon).getString(PHASE);
        if (name.isEmpty()) return Phase.GROUND;
        try {
            return Phase.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Phase.GROUND;
        }
    }

    public static void setPhase(EntityDragonBase dragon, Phase phase) {
        if (dragon != null) tag(dragon).putString(PHASE, phase == null ? Phase.GROUND.name() : phase.name());
    }

    public static boolean hasGoal(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).contains(GOAL_X);
    }

    public static Vec3 goal(EntityDragonBase dragon) {
        return new Vec3(tag(dragon).getDouble(GOAL_X), tag(dragon).getDouble(GOAL_Y), tag(dragon).getDouble(GOAL_Z));
    }

    public static void setGoal(EntityDragonBase dragon, Vec3 goal) {
        if (dragon == null || goal == null) return;
        tag(dragon).putDouble(GOAL_X, goal.x);
        tag(dragon).putDouble(GOAL_Y, goal.y);
        tag(dragon).putDouble(GOAL_Z, goal.z);
    }

    public static void clearGoal(EntityDragonBase dragon) {
        if (dragon == null) return;
        tag(dragon).remove(GOAL_X);
        tag(dragon).remove(GOAL_Y);
        tag(dragon).remove(GOAL_Z);
    }

    public static Vec3 landingSpot(EntityDragonBase dragon) {
        return new Vec3(tag(dragon).getDouble(LANDING_X), tag(dragon).getDouble(LANDING_Y), tag(dragon).getDouble(LANDING_Z));
    }

    public static boolean hasLandingSpot(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).contains(LANDING_X);
    }

    public static void setLandingSpot(EntityDragonBase dragon, Vec3 spot) {
        if (dragon == null || spot == null) return;
        tag(dragon).putDouble(LANDING_X, spot.x);
        tag(dragon).putDouble(LANDING_Y, spot.y);
        tag(dragon).putDouble(LANDING_Z, spot.z);
    }

    public static void clearLandingSpot(EntityDragonBase dragon) {
        if (dragon == null) return;
        tag(dragon).remove(LANDING_X);
        tag(dragon).remove(LANDING_Y);
        tag(dragon).remove(LANDING_Z);
    }

    public static UUID attackTarget(EntityDragonBase dragon) {
        return dragon != null && tag(dragon).hasUUID(ATTACK_TARGET) ? tag(dragon).getUUID(ATTACK_TARGET) : null;
    }

    public static void setAttackTarget(EntityDragonBase dragon, UUID target) {
        if (dragon == null) return;
        if (target == null) tag(dragon).remove(ATTACK_TARGET);
        else tag(dragon).putUUID(ATTACK_TARGET, target);
    }

    public static void clearAttackTarget(EntityDragonBase dragon) {
        if (dragon != null) tag(dragon).remove(ATTACK_TARGET);
    }

    public static int prevCommand(EntityDragonBase dragon) {
        return dragon != null ? tag(dragon).getInt(PREV_COMMAND) : 0;
    }

    public static void setPrevCommand(EntityDragonBase dragon, int command) {
        if (dragon != null) tag(dragon).putInt(PREV_COMMAND, command);
    }
}

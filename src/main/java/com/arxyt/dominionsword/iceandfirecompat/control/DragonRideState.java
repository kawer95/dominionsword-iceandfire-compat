package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Persistent Dominion-controlled dragon state.
 *
 * <p>All state lives under the {@value #STATE_TAG} sub-tag on the dragon's persistent data; the
 * rider marker uses the same sub-tag on the rider entity. Field ownership:
 *
 * <table>
 *   <caption>State fields</caption>
 *   <tr><th>Key</th><th>Owner</th><th>Persistent</th><th>Client sync</th></tr>
 *   <tr><td>controlled</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>rider</td><td>dragon</td><td>yes</td><td>via {@link DragonRiderSync}</td></tr>
 *   <tr><td>rider_dragon</td><td>rider</td><td>yes</td><td>no</td></tr>
 *   <tr><td>auto</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>phase</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>goal_*</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>landing_*</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>landing_cooldown</td><td>dragon</td><td>no</td><td>no</td></tr>
 *   <tr><td>attack</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>prev_command</td><td>dragon</td><td>yes</td><td>no</td></tr>
 *   <tr><td>path_tick / path_fails</td><td>dragon</td><td>no</td><td>no</td></tr>
 * </table>
 */
public final class DragonRideState {
    public static final String STATE_TAG = "dominionsword_iaf";
    private static final String CONTROLLED = "controlled";
    private static final String RIDER = "rider";
    private static final String RIDER_DRAGON = "rider_dragon";
    private static final String AUTO_CONTROL = "auto";
    private static final String PHASE = "phase";
    private static final String GOAL = "goal";
    private static final String LANDING = "landing";
    private static final String LANDING_COOLDOWN = "landing_cooldown";
    private static final String ATTACK_TARGET = "attack";
    private static final String PREV_COMMAND = "prev_command";
    private static final String PATH_ATTEMPT_TICK = "path_tick";
    private static final String PATH_FAILS = "path_fails";
    private static final String MISSION = "mission";
    private static final String TASK = "task";
    private static final String AOE_RADIUS = "aoe_radius";
    private static final String AOE_HALF_HEIGHT = "aoe_half_height";
    private static final String STRAFE_READY = "strafe_ready";

    public enum Phase {
        GROUND,
        TAKEOFF,
        CRUISE,
        LANDING
    }

    /** Persistent mission intent; the autopilot maps each mode to a flight behaviour. */
    public enum Mission {
        TRANSIT,
        HOVER_ATTACK,
        ORBIT,
        STRAFE_APPROACH,
        STRAFE_RUN,
        STRAFE_EGRESS,
        AREA_HOLD,
        EMERGENCY_HOVER
    }

    private DragonRideState() {
    }

    private static CompoundTag state(Entity entity) {
        CompoundTag root = entity.getPersistentData();
        if (!root.contains(STATE_TAG, CompoundTag.TAG_COMPOUND)) root.put(STATE_TAG, new CompoundTag());
        return root.getCompound(STATE_TAG);
    }

    static Vec3 readVec3(CompoundTag state, String prefix) {
        return new Vec3(state.getDouble(prefix + "_x"), state.getDouble(prefix + "_y"), state.getDouble(prefix + "_z"));
    }

    static void writeVec3(CompoundTag state, String prefix, Vec3 value) {
        if (value == null) return;
        state.putDouble(prefix + "_x", value.x);
        state.putDouble(prefix + "_y", value.y);
        state.putDouble(prefix + "_z", value.z);
    }

    static void clearVec3(CompoundTag state, String prefix) {
        state.remove(prefix + "_x");
        state.remove(prefix + "_y");
        state.remove(prefix + "_z");
    }

    public static boolean isControlled(EntityDragonBase dragon) {
        return dragon != null && state(dragon).getBoolean(CONTROLLED);
    }

    public static void setControlled(EntityDragonBase dragon, boolean controlled) {
        if (dragon != null) state(dragon).putBoolean(CONTROLLED, controlled);
    }

    /** Server reads the authoritative NBT marker; client reads the synced {@link DragonRiderSync} value. */
    public static UUID riderId(EntityDragonBase dragon) {
        if (dragon == null) return null;
        if (dragon.level().isClientSide()) return DragonRiderSync.getRiderId(dragon);
        CompoundTag state = state(dragon);
        return state.hasUUID(RIDER) ? state.getUUID(RIDER) : null;
    }

    public static void setRiderId(EntityDragonBase dragon, UUID rider) {
        if (dragon == null) return;
        if (rider == null) {
            state(dragon).remove(RIDER);
            DragonRiderSync.setRiderId(dragon, null);
        } else {
            state(dragon).putUUID(RIDER, rider);
            DragonRiderSync.setRiderId(dragon, rider);
        }
    }

    public static Mob riderEntity(EntityDragonBase dragon) {
        UUID id = riderId(dragon);
        if (id == null || dragon == null || !(dragon.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(id);
        return entity instanceof Mob mob ? mob : null;
    }

    public static void setRiderDragon(Mob rider, UUID dragonId) {
        if (rider != null && dragonId != null) state(rider).putUUID(RIDER_DRAGON, dragonId);
    }

    public static void clearRiderMarkers(EntityDragonBase dragon, Mob rider) {
        if (dragon != null) {
            state(dragon).remove(RIDER);
            DragonRiderSync.setRiderId(dragon, null);
        }
        if (rider != null) state(rider).remove(RIDER_DRAGON);
    }

    public static boolean autoControl(EntityDragonBase dragon) {
        return dragon != null && state(dragon).getBoolean(AUTO_CONTROL);
    }

    public static void setAutoControl(EntityDragonBase dragon, boolean auto) {
        if (dragon != null) state(dragon).putBoolean(AUTO_CONTROL, auto);
    }

    public static Phase phase(EntityDragonBase dragon) {
        if (dragon == null) return Phase.GROUND;
        String name = state(dragon).getString(PHASE);
        if (name.isEmpty()) return Phase.GROUND;
        try {
            return Phase.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Phase.GROUND;
        }
    }

    public static void setPhase(EntityDragonBase dragon, Phase phase) {
        if (dragon != null) state(dragon).putString(PHASE, phase == null ? Phase.GROUND.name() : phase.name());
    }

    public static boolean hasGoal(EntityDragonBase dragon) {
        return dragon != null && state(dragon).contains(GOAL + "_x");
    }

    public static Vec3 goal(EntityDragonBase dragon) {
        return readVec3(state(dragon), GOAL);
    }

    public static void setGoal(EntityDragonBase dragon, Vec3 goal) {
        if (dragon != null) writeVec3(state(dragon), GOAL, goal);
    }

    public static void clearGoal(EntityDragonBase dragon) {
        if (dragon != null) clearVec3(state(dragon), GOAL);
    }

    public static boolean hasLandingSpot(EntityDragonBase dragon) {
        return dragon != null && state(dragon).contains(LANDING + "_x");
    }

    public static Vec3 landingSpot(EntityDragonBase dragon) {
        return readVec3(state(dragon), LANDING);
    }

    public static void setLandingSpot(EntityDragonBase dragon, Vec3 spot) {
        if (dragon != null) writeVec3(state(dragon), LANDING, spot);
    }

    public static void clearLandingSpot(EntityDragonBase dragon) {
        if (dragon != null) clearVec3(state(dragon), LANDING);
    }

    public static long landingCooldownUntil(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getLong(LANDING_COOLDOWN) : 0L;
    }

    public static void setLandingCooldown(EntityDragonBase dragon, long tick) {
        if (dragon != null) state(dragon).putLong(LANDING_COOLDOWN, tick);
    }

    public static void clearLandingCooldown(EntityDragonBase dragon) {
        if (dragon != null) state(dragon).remove(LANDING_COOLDOWN);
    }

    public static UUID attackTarget(EntityDragonBase dragon) {
        CompoundTag state = dragon == null ? null : state(dragon);
        return state != null && state.hasUUID(ATTACK_TARGET) ? state.getUUID(ATTACK_TARGET) : null;
    }

    public static void setAttackTarget(EntityDragonBase dragon, UUID target) {
        if (dragon == null) return;
        if (target == null) state(dragon).remove(ATTACK_TARGET);
        else state(dragon).putUUID(ATTACK_TARGET, target);
    }

    public static void clearAttackTarget(EntityDragonBase dragon) {
        if (dragon != null) state(dragon).remove(ATTACK_TARGET);
    }

    public static int prevCommand(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getInt(PREV_COMMAND) : 0;
    }

    public static void setPrevCommand(EntityDragonBase dragon, int command) {
        if (dragon != null) state(dragon).putInt(PREV_COMMAND, command);
    }

    public static long pathAttemptTick(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getLong(PATH_ATTEMPT_TICK) : 0L;
    }

    public static void setPathAttemptTick(EntityDragonBase dragon, long tick) {
        if (dragon != null) state(dragon).putLong(PATH_ATTEMPT_TICK, tick);
    }

    public static int pathFailCount(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getInt(PATH_FAILS) : 0;
    }

    public static void setPathFailCount(EntityDragonBase dragon, int fails) {
        if (dragon != null) state(dragon).putInt(PATH_FAILS, fails);
    }

    public static void clearPathBackoff(EntityDragonBase dragon) {
        if (dragon != null) clearPathBackoff(state(dragon));
    }

    public static Mission mission(EntityDragonBase dragon) {
        if (dragon == null) return Mission.TRANSIT;
        String name = state(dragon).getString(MISSION);
        if (name.isEmpty()) return Mission.TRANSIT;
        try {
            return Mission.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Mission.TRANSIT;
        }
    }

    public static void setMission(EntityDragonBase dragon, Mission mission) {
        if (dragon != null) state(dragon).putString(MISSION, mission == null ? Mission.TRANSIT.name() : mission.name());
    }

    public static boolean hasTask(EntityDragonBase dragon) {
        return dragon != null && state(dragon).contains(TASK + "_x");
    }

    public static Vec3 task(EntityDragonBase dragon) {
        return readVec3(state(dragon), TASK);
    }

    public static void setTask(EntityDragonBase dragon, Vec3 task) {
        if (dragon != null) writeVec3(state(dragon), TASK, task);
    }

    public static void clearTask(EntityDragonBase dragon) {
        if (dragon != null) clearVec3(state(dragon), TASK);
    }

    public static double aoeRadius(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getDouble(AOE_RADIUS) : 0.0D;
    }

    public static double aoeHalfHeight(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getDouble(AOE_HALF_HEIGHT) : 0.0D;
    }

    public static void setAoeSpec(EntityDragonBase dragon, double radius, double halfHeight) {
        if (dragon == null) return;
        state(dragon).putDouble(AOE_RADIUS, Math.max(1.0D, Math.min(64.0D, radius)));
        state(dragon).putDouble(AOE_HALF_HEIGHT, Math.max(1.0D, Math.min(64.0D, halfHeight)));
    }

    public static long strafeReadyTick(EntityDragonBase dragon) {
        return dragon != null ? state(dragon).getLong(STRAFE_READY) : 0L;
    }

    public static void setStrafeReadyTick(EntityDragonBase dragon, long tick) {
        if (dragon != null) state(dragon).putLong(STRAFE_READY, tick);
    }

    /**
     * Single cleanup entry point for control end.
     *
     * @param keepRider       keep the designated rider marker pair
     * @param keepOfflineTask keep goal/landing/attack so a preserved offline order can continue
     */
    public static void clearControlState(EntityDragonBase dragon, boolean keepRider, boolean keepOfflineTask) {
        if (dragon == null) return;
        clearStateFields(state(dragon), keepOfflineTask);
        if (!keepRider) {
            Mob rider = riderEntity(dragon);
            clearRiderMarkers(dragon, rider);
        }
    }

    static void clearStateFields(CompoundTag state, boolean keepOfflineTask) {
        if (!keepOfflineTask) {
            clearVec3(state, GOAL);
            clearVec3(state, LANDING);
            clearVec3(state, TASK);
            state.remove(ATTACK_TARGET);
            state.remove(MISSION);
            state.remove(AOE_RADIUS);
            state.remove(AOE_HALF_HEIGHT);
            state.remove(STRAFE_READY);
        }
        state.remove(LANDING_COOLDOWN);
        clearPathBackoff(state);
        state.remove(PHASE);
    }

    private static void clearPathBackoff(CompoundTag state) {
        state.remove(PATH_ATTEMPT_TICK);
        state.remove(PATH_FAILS);
    }
}

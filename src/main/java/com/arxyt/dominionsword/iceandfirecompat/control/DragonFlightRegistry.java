package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volatile per-dragon runtime state: paths, guidance caches and controller integration. Nothing
 * here is persisted; entity reloads re-plan from the persistent mission state.
 */
public final class DragonFlightRegistry {
    private static final long STALE_TICKS = 400L;
    private static final Map<UUID, RuntimeState> STATES = new ConcurrentHashMap<>();

    private DragonFlightRegistry() {
    }

    public static final class RuntimeState {
        public long lastTick;
        public java.util.List<Vec3> path = java.util.List.of();
        public int pathIndex;
        public long replanTick;
        public int emergencyTicks;
        public double speed;
        public Vec3 lastPosition;
        public int strafeStage;
        public Vec3 strafeAxis;
        public long strafeStartedTick;
        public long lastFireTick;
        public final Map<Long, Boolean> occupancy = new HashMap<>();
        public Vec3 lastGoal;
        public double takeoffStartY;

        public RuntimeState(Vec3 position) {
            this.lastPosition = position;
        }
    }

    public static RuntimeState state(EntityDragonBase dragon) {
        return STATES.computeIfAbsent(dragon.getUUID(), id -> new RuntimeState(dragon.position()));
    }

    public static void remove(UUID id) {
        if (id != null) STATES.remove(id);
    }

    public static void prune(long now) {
        STATES.entrySet().removeIf(entry -> now - entry.getValue().lastTick > STALE_TICKS);
    }

    /** Snapshot of other dragons' latest positions for separation forces. */
    public static Map<UUID, Vec3> positions(EntityDragonBase self) {
        Map<UUID, Vec3> result = new HashMap<>();
        for (Map.Entry<UUID, RuntimeState> entry : STATES.entrySet()) {
            if (entry.getKey().equals(self.getUUID())) continue;
            if (entry.getValue().lastPosition != null) result.put(entry.getKey(), entry.getValue().lastPosition);
        }
        return result;
    }
}

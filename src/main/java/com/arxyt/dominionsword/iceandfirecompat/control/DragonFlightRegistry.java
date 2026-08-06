package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-only, non-persistent flight state.  Persistent NBT is deliberately not used as a tick cache. */
public final class DragonFlightRegistry {
    private static final long STALE_TICKS = 400L;
    private static final int BUCKET_SIZE = 12;
    private static final Map<UUID, RuntimeState> STATES = new ConcurrentHashMap<>();
    private static final Map<Object, Map<Bucket, Set<UUID>>> BUCKETS = new HashMap<>();
    private static final Map<Object, Long> COLLISION_EPOCHS = new HashMap<>();

    private DragonFlightRegistry() {}

    public static final class RuntimeState {
        public long lastTick;
        public Object dimension;
        public Vec3 lastPosition;
        public Vec3 hoverAnchor;
        public Vec3 lastGoal;
        public Vec3 lastSafeDirection = new Vec3(0.0D, 0.0D, 1.0D);
        public double speed;
        public double takeoffStartY = Double.NaN;
        public int pathIndex;
        public int noPathTicks;
        public int stalledTicks;
        public long replanTick;
        public long lastFireTick;
        public long lastHostileQueryTick;
        public int strafeStage;
        public long strafeStageStartTick;
        public Vec3 strafeAxis;
        public Vec3 cachedCorridorDirection;
        public Vec3 cachedCorridorOrigin;
        public long cachedCorridorTick = Long.MIN_VALUE;
        public long cachedCorridorEpoch;
        public DragonPathPlanner.Job pathJob;
        public java.util.List<Vec3> path = java.util.List.of();
        private Bucket bucket;

        RuntimeState(EntityDragonBase dragon) {
            this.lastPosition = dragon.position();
            this.dimension = dragon.level().dimension();
        }

        void resetNavigation() {
            path = java.util.List.of();
            pathIndex = 0;
            pathJob = null;
            lastGoal = null;
            noPathTicks = 0;
            stalledTicks = 0;
        }

        void resetManeuver() {
            hoverAnchor = null;
            strafeStage = 0;
            strafeStageStartTick = 0L;
            strafeAxis = null;
            lastFireTick = 0L;
        }
    }

    public static RuntimeState state(EntityDragonBase dragon) {
        RuntimeState state = STATES.computeIfAbsent(dragon.getUUID(), ignored -> new RuntimeState(dragon));
        Object dimension = dragon.level().dimension();
        if (!dimension.equals(state.dimension)) {
            removeFromBucket(dragon.getUUID(), state);
            state.dimension = dimension;
            state.resetNavigation();
            state.resetManeuver();
        }
        index(dragon, state);
        return state;
    }

    public static void remove(UUID id) {
        if (id == null) return;
        RuntimeState state = STATES.remove(id);
        if (state != null) removeFromBucket(id, state);
    }

    public static void remove(EntityDragonBase dragon) {
        if (dragon != null) remove(dragon.getUUID());
    }

    /** Called once from ServerTick.END, never from individual dragon controllers. */
    public static void beginServerTick(MinecraftServer server) {
        if (server == null) return;
        long now = server.overworld().getGameTime();
        Set<UUID> stale = new HashSet<>();
        for (Map.Entry<UUID, RuntimeState> entry : STATES.entrySet()) {
            if (now - entry.getValue().lastTick > STALE_TICKS) stale.add(entry.getKey());
        }
        for (UUID id : stale) remove(id);
        DragonPathPlanner.beginServerTick();
    }

    /** Invalidates the tiny local-corridor cache after a server-side block change. */
    public static void invalidateCorridors(Object dimension) {
        if (dimension != null) COLLISION_EPOCHS.merge(dimension, 1L, Long::sum);
    }

    public static long collisionEpoch(Object dimension) {
        return COLLISION_EPOCHS.getOrDefault(dimension, 0L);
    }

    /** Searches only adjacent 12-block buckets, so separation is independent of dragons in other areas. */
    public static Vec3 separation(EntityDragonBase self, double radius) {
        RuntimeState own = state(self);
        Vec3 push = Vec3.ZERO;
        double radiusSq = radius * radius;
        if (own.bucket == null) return push;
        Map<Bucket, Set<UUID>> dimensionBuckets = BUCKETS.get(own.dimension);
        if (dimensionBuckets == null) return push;
        int reach = Math.max(1, (int) Math.ceil(radius / BUCKET_SIZE));
        for (int x = -reach; x <= reach; x++) for (int y = -reach; y <= reach; y++) for (int z = -reach; z <= reach; z++) {
            Set<UUID> ids = dimensionBuckets.get(new Bucket(own.bucket.x + x, own.bucket.y + y, own.bucket.z + z));
            if (ids == null) continue;
            for (UUID id : ids) {
                if (id.equals(self.getUUID())) continue;
                RuntimeState other = STATES.get(id);
                if (other == null || other.lastPosition == null) continue;
                Vec3 delta = self.position().subtract(other.lastPosition);
                double distanceSq = delta.lengthSqr();
                if (distanceSq < 1.0E-6D || distanceSq >= radiusSq) continue;
                double distance = Math.sqrt(distanceSq);
                push = push.add(delta.scale((1.0D - distance / radius) * 0.12D / distance));
            }
        }
        return push;
    }

    private static void index(EntityDragonBase dragon, RuntimeState state) {
        Bucket next = bucket(dragon.position());
        if (next.equals(state.bucket)) return;
        removeFromBucket(dragon.getUUID(), state);
        state.bucket = next;
        BUCKETS.computeIfAbsent(state.dimension, ignored -> new HashMap<>())
                .computeIfAbsent(next, ignored -> new HashSet<>()).add(dragon.getUUID());
    }

    private static void removeFromBucket(UUID id, RuntimeState state) {
        if (state.bucket == null || state.dimension == null) return;
        Map<Bucket, Set<UUID>> dimensionBuckets = BUCKETS.get(state.dimension);
        if (dimensionBuckets == null) return;
        Set<UUID> ids = dimensionBuckets.get(state.bucket);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) dimensionBuckets.remove(state.bucket);
        }
        if (dimensionBuckets.isEmpty()) BUCKETS.remove(state.dimension);
        state.bucket = null;
    }

    private static Bucket bucket(Vec3 position) {
        return new Bucket((int) Math.floor(position.x / BUCKET_SIZE), (int) Math.floor(position.y / BUCKET_SIZE),
                (int) Math.floor(position.z / BUCKET_SIZE));
    }

    private record Bucket(int x, int y, int z) {}
}

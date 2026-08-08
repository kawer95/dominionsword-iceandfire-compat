package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared local safety layer. Every flight mode must pass through this class before motion is applied. */
public final class DragonGuidance {
    private static final double[][] CANDIDATES = {
            {0, 0}, {-15, 0}, {15, 0}, {-30, 0}, {30, 0}, {0, -10}, {0, 10}, {-20, -10}, {20, -10}
    };

    private DragonGuidance() {}

    public static Vec3 steer(EntityDragonBase dragon, Vec3 desiredDirection, DragonFlightRegistry.RuntimeState state) {
        Vec3 desired = DragonMoveMath.normalizeOr(desiredDirection, state.lastSafeDirection);
        Vec3 current = DragonMoveMath.normalizeOr(dragon.getDeltaMovement(), state.lastSafeDirection);
        long now = dragon.level().getGameTime();
        long epoch = DragonFlightRegistry.collisionEpoch(dragon.level().dimension());
        if (state.cachedCorridorDirection != null && now - state.cachedCorridorTick <= 2L
                && state.cachedCorridorEpoch == epoch && state.cachedCorridorOrigin != null
                && state.cachedCorridorOrigin.distanceToSqr(dragon.position()) <= 1.0D
                && state.cachedCorridorDirection.dot(desired) >= 0.985D) {
            return state.cachedCorridorDirection;
        }
        double baseYaw = Math.toDegrees(Math.atan2(-desired.x, desired.z));
        double basePitch = DragonMoveMath.pitchTo(desired);
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double[] candidate : CANDIDATES) {
            Vec3 direction = direction(DragonMoveMath.wrapDegrees(baseYaw + candidate[0]),
                    DragonMoveMath.clampPitch(basePitch + candidate[1]));
            if (!corridorClear(dragon, direction, stoppingLookAhead(dragon, state))) continue;
            double progress = desired.dot(direction);
            double continuity = current.dot(direction);
            double score = progress * 0.70D + continuity * 0.30D - Math.abs(candidate[0]) * 0.002D;
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        if (best != null) {
            state.lastSafeDirection = best;
            state.cachedCorridorDirection = best;
            state.cachedCorridorOrigin = dragon.position();
            state.cachedCorridorTick = now;
            state.cachedCorridorEpoch = epoch;
        }
        return best;
    }

    /** Direct flight is the normal case; the grid planner is reserved for a genuinely blocked corridor. */
    public static boolean directCorridorClear(EntityDragonBase dragon, Vec3 direction, DragonFlightRegistry.RuntimeState state) {
        return corridorClear(dragon, DragonMoveMath.normalizeOr(direction, state.lastSafeDirection), stoppingLookAhead(dragon, state));
    }

    public static Vec3 lookAhead(EntityDragonBase dragon, Vec3 goal, DragonFlightRegistry.RuntimeState state) {
        if (state.path.isEmpty()) return goal;
        Vec3 position = dragon.position();
        while (state.pathIndex < state.path.size() - 1 && position.distanceToSqr(state.path.get(state.pathIndex + 1)) < 9.0D) {
            state.pathIndex++;
        }
        double wanted = stoppingLookAhead(dragon, state);
        double travelled = 0.0D;
        Vec3 previous = position;
        for (int i = state.pathIndex; i < state.path.size(); i++) {
            Vec3 point = state.path.get(i);
            double segment = previous.distanceTo(point);
            if (travelled + segment >= wanted) {
                double t = (wanted - travelled) / Math.max(1.0E-6D, segment);
                return previous.lerp(point, Math.max(0.0D, Math.min(1.0D, t)));
            }
            travelled += segment;
            previous = point;
        }
        return goal;
    }

    public static boolean corridorClear(EntityDragonBase dragon, Vec3 direction, double lookAhead) {
        if (direction == null || direction.lengthSqr() < 1.0E-8D) return false;
        Level level = dragon.level();
        int samples = 6;
        for (int i = 1; i <= samples; i++) {
            Vec3 point = dragon.position().add(direction.scale(lookAhead * i / samples));
            AABB box = dragon.getBoundingBox().move(point.x - dragon.getX(), point.y - dragon.getY(), point.z - dragon.getZ());
            if (!level.hasChunksAt(net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ),
                    net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ)) || !level.noCollision(dragon, box)) return false;
        }
        return true;
    }

    private static double stoppingLookAhead(EntityDragonBase dragon, DragonFlightRegistry.RuntimeState state) {
        double brakingDistance = state.speed * state.speed / (2.0D * DragonMoveMath.BRAKE_DECELERATION);
        double dragonLength = Math.max(dragon.getBbWidth(), dragon.getBbHeight()) * 0.5D;
        return Math.max(8.0D, Math.min(32.0D, brakingDistance + dragonLength + 4.0D));
    }

    private static Vec3 direction(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad), Math.cos(yawRad) * horizontal);
    }
}

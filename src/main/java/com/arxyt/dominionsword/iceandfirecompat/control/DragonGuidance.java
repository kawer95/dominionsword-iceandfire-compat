package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Look-ahead steering: follows the planned path without stopping at waypoints, samples a small set
 * of local corridors for clearance, and returns the desired flight direction (or zero to brake).
 */
public final class DragonGuidance {
    private static final double[] YAW_OFFSETS = {-20.0D, 0.0D, 20.0D};
    private static final double[] PITCH_OFFSETS = {-10.0D, 0.0D, 10.0D};
    private static final double[] SAMPLE_DISTANCES = {3.0D, 6.0D, 9.0D, 12.0D};

    private DragonGuidance() {
    }

    public static Vec3 steer(EntityDragonBase dragon, Vec3 goal, DragonFlightRegistry.RuntimeState state) {
        Vec3 lookAhead = lookAhead(dragon, goal, state);
        Vec3 base = lookAhead.subtract(dragon.position());
        double baseLength = base.length();
        if (baseLength < 1.0E-4D) return Vec3.ZERO;
        Vec3 baseDir = base.scale(1.0D / baseLength);
        double baseYaw = Math.toDegrees(Math.atan2(-baseDir.x, baseDir.z));
        double basePitch = DragonMoveMath.pitchTo(baseDir);

        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int candidates = 0;
        for (double yawOffset : YAW_OFFSETS) {
            for (double pitchOffset : PITCH_OFFSETS) {
                candidates++;
                if (candidates > 9) break;
                double yaw = DragonMoveMath.wrapDegrees(baseYaw + yawOffset);
                double pitch = DragonMoveMath.clampPitch(basePitch + pitchOffset);
                Vec3 candidate = direction(yaw, pitch);
                if (!corridorClear(dragon, candidate)) continue;
                double progress = baseDir.dot(candidate);
                double turnCost = Math.abs(yawOffset) * 0.5D + Math.abs(pitchOffset) * 0.3D;
                double score = progress - turnCost * 0.01D;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best == null ? Vec3.ZERO : best;
    }

    private static Vec3 lookAhead(EntityDragonBase dragon, Vec3 goal, DragonFlightRegistry.RuntimeState state) {
        double distance = Math.max(8.0D, Math.min(32.0D, state.speed * 10.0D));
        if (state.path.isEmpty()) return goal;
        Vec3 position = dragon.position();
        double travelled = 0.0D;
        Vec3 previous = position;
        for (Vec3 point : state.path) {
            double segment = previous.distanceTo(point);
            travelled += segment;
            if (travelled >= distance) {
                double t = 1.0D - (travelled - distance) / Math.max(1.0E-6D, segment);
                return previous.lerp(point, Math.max(0.0D, Math.min(1.0D, t)));
            }
            previous = point;
        }
        return goal;
    }

    private static boolean corridorClear(EntityDragonBase dragon, Vec3 dir) {
        Level level = dragon.level();
        Vec3 position = dragon.position();
        for (double sample : SAMPLE_DISTANCES) {
            Vec3 point = position.add(dir.scale(sample));
            AABB box = dragon.getBoundingBox().move(point.x - dragon.getX(), point.y - dragon.getY(), point.z - dragon.getZ());
            if (!level.noCollision(dragon, box)) return false;
        }
        return true;
    }

    private static Vec3 direction(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad), Math.cos(yawRad) * horizontal);
    }
}

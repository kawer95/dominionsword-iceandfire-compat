package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds a clear landing pad near a requested position that fits the dragon's vertical clearance. */
public final class DragonLandingPlanner {
    private static final int SEARCH_RADIUS = 12;
    private static final int STEP = 2;

    private DragonLandingPlanner() {
    }

    public static Vec3 findLandingSpot(EntityDragonBase dragon, Vec3 center) {
        if (dragon == null || center == null) return null;
        Level level = dragon.level();
        double clearance = Math.max(8.0D, dragon.getBbHeight() + 4.0D);
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        for (int radius = 0; radius <= SEARCH_RADIUS; radius += STEP) {
            for (int dx = -radius; dx <= radius; dx += STEP) {
                for (int dz = -radius; dz <= radius; dz += STEP) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos surface = surface(level, new BlockPos(cx + dx, cy, cz + dz));
                    if (surface != null && clearColumn(level, surface, clearance)) {
                        return Vec3.atBottomCenterOf(surface).add(0.0D, 0.1D, 0.0D);
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos surface(Level level, BlockPos pos) {
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos candidate = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && !state.getCollisionShape(level, candidate).isEmpty() && !state.canBeReplaced()) {
                return candidate.above();
            }
        }
        return null;
    }

    private static boolean clearColumn(Level level, BlockPos ground, double height) {
        for (int dy = 1; dy < height; dy++) {
            BlockState state = level.getBlockState(ground.offset(0, dy, 0));
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }
}

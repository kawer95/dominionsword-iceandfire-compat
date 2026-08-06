package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded landing-pad search.
 *
 * <p>The search window is limited vertically around the requested center, surfaces are found via
 * the motion-blocking heightmap, every candidate must fit the dragon's full AABB and avoid fluids
 * and fire, and the whole search respects hard read/candidate budgets so it can never spike TPS.
 */
public final class DragonLandingPlanner {
    static final int SEARCH_RADIUS = 12;
    static final int STEP = 2;
    static final int VERTICAL_WINDOW = 24;
    static final int MAX_CANDIDATES = 64;
    static final int MAX_READS = 512;
    static final int COOLDOWN_TICKS = 40;

    private DragonLandingPlanner() {
    }

    public static Vec3 findLandingSpot(EntityDragonBase dragon, Vec3 center) {
        if (dragon == null || center == null) return null;
        Level level = dragon.level();
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight();
        int minY = Math.max(minBuild, (int) Math.floor(center.y) - VERTICAL_WINDOW);
        int maxY = Math.min(maxBuild - 1, (int) Math.floor(center.y) + VERTICAL_WINDOW);
        int cx = (int) Math.floor(center.x);
        int cz = (int) Math.floor(center.z);
        Budget budget = new Budget();
        for (int radius = 0; radius <= SEARCH_RADIUS && !budget.exceeded(); radius += STEP) {
            for (int dx = -radius; dx <= radius && !budget.exceeded(); dx += STEP) {
                for (int dz = -radius; dz <= radius && !budget.exceeded(); dz += STEP) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    budget.candidates++;
                    int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + dx, cz + dz);
                    budget.reads++;
                    if (surfaceY < minY || surfaceY > maxY) continue;
                    // getHeight already returns the first non-motion-blocking Y above the surface.
                    Vec3 spot = new Vec3(cx + dx + 0.5D, surfaceY, cz + dz + 0.5D);
                    BlockPos ground = BlockPos.containing(spot).below();
                    BlockState groundState = level.getBlockState(ground);
                    budget.reads++;
                    if (groundState.isAir() || groundState.canBeReplaced()
                            || groundState.getCollisionShape(level, ground).isEmpty()) continue;
                    if (clearPad(dragon, level, spot, ground, budget)) return spot;
                }
            }
        }
        return null;
    }

    /** Ring positions for tests and for the search above; sampled with the configured step. */
    static List<BlockPos> ring(int cx, int cz, int radius, int step) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if (radius == 0 || Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                    out.add(new BlockPos(cx + dx, 0, cz + dz));
                }
            }
        }
        return out;
    }

    static int candidateCount() {
        int count = 0;
        for (int radius = 0; radius <= SEARCH_RADIUS; radius += STEP) {
            count += ring(0, 0, radius, STEP).size();
        }
        return count;
    }

    private static boolean clearPad(EntityDragonBase dragon, Level level, Vec3 spot, BlockPos ground, Budget budget) {
        AABB moved = dragon.getBoundingBox().move(spot.x - dragon.getX(), spot.y - dragon.getY(), spot.z - dragon.getZ());
        if (!level.noCollision(dragon, moved)) return false;
        int bottom = (int) Math.floor(moved.minY);
        int top = (int) Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(moved.maxY));
        for (int y = Math.max(level.getMinBuildHeight(), bottom); y <= top; y++) {
            budget.reads++;
            if (budget.exceeded()) return false;
            for (int x = (int) Math.floor(moved.minX); x <= (int) Math.floor(moved.maxX); x++) {
                for (int z = (int) Math.floor(moved.minZ); z <= (int) Math.floor(moved.maxZ); z++) {
                    budget.reads++;
                    if (budget.exceeded()) return false;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).getBlock() == Blocks.FIRE || !level.getFluidState(pos).isEmpty()) return false;
                }
            }
        }
        // A large dragon must not balance on one center block: require center and four footprint corners.
        return supported(level, ground) && supported(level, new BlockPos((int) Math.floor(moved.minX), ground.getY(), (int) Math.floor(moved.minZ)))
                && supported(level, new BlockPos((int) Math.floor(moved.maxX), ground.getY(), (int) Math.floor(moved.minZ)))
                && supported(level, new BlockPos((int) Math.floor(moved.minX), ground.getY(), (int) Math.floor(moved.maxZ)))
                && supported(level, new BlockPos((int) Math.floor(moved.maxX), ground.getY(), (int) Math.floor(moved.maxZ)));
    }

    private static boolean supported(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced() && !state.getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty() && state.getBlock() != Blocks.FIRE;
    }

    private static final class Budget {
        int reads;
        int candidates;

        boolean exceeded() {
            return reads >= MAX_READS || candidates >= MAX_CANDIDATES;
        }
    }
}

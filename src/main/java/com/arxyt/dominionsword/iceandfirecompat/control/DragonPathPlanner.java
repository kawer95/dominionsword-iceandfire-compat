package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Coarse 3D A* on a dragon-sized grid. Occupancy is cached per cell and only the final path and
 * guidance corridors use full-AABB checks, keeping node expansion within a hard budget.
 */
public final class DragonPathPlanner {
    public static final int MAX_EXPANSIONS = 1024;
    private static final int NEIGHBOR_OFFSETS = 26;
    private static final int[][] OFFSETS = offsets();

    private DragonPathPlanner() {
    }

    public static List<Vec3> plan(EntityDragonBase dragon, Vec3 goal, DragonFlightRegistry.RuntimeState state) {
        if (dragon == null || goal == null || dragon.level() == null || dragon.level().isClientSide()) {
            return List.of();
        }
        Level level = dragon.level();
        double cellXZ = Math.max(4.0D, Math.ceil(dragon.getBbWidth()));
        double cellY = Math.max(4.0D, Math.ceil(dragon.getBbHeight()));
        int startX = toCell(dragon.getX(), cellXZ);
        int startY = toCell(dragon.getY(), cellY);
        int startZ = toCell(dragon.getZ(), cellXZ);
        int goalX = toCell(goal.x, cellXZ);
        int goalY = toCell(goal.y, cellY);
        int goalZ = toCell(goal.z, cellXZ);
        if (startX == goalX && startY == goalY && startZ == goalZ) {
            return List.of(goal);
        }

        long startKey = key(startX, startY, startZ);
        long goalKey = key(goalX, goalY, goalZ);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        gScore.put(startKey, 0.0D);
        open.add(new Node(startKey, heuristic(startX, startY, startZ, goalX, goalY, goalZ)));
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (!closed.add(current.key)) continue;
            expansions++;
            if (current.key == goalKey) {
                return smooth(dragon, level, reconstruct(cameFrom, current.key, startKey, cellXZ, cellY));
            }
            int cx = xOf(current.key);
            int cy = yOf(current.key);
            int cz = zOf(current.key);
            double currentG = gScore.getOrDefault(current.key, Double.MAX_VALUE);
            for (int[] offset : OFFSETS) {
                int nx = cx + offset[0];
                int ny = cy + offset[1];
                int nz = cz + offset[2];
                if (ny < level.getMinBuildHeight() || ny > level.getMaxBuildHeight()) continue;
                long nextKey = key(nx, ny, nz);
                if (!isOpen(dragon, level, cellXZ, cellY, nx, ny, nz, state)) continue;
                double stepCost = 1.0D + Math.abs(offset[1]) * 2.0D;
                double tentative = currentG + stepCost;
                if (tentative < gScore.getOrDefault(nextKey, Double.MAX_VALUE)) {
                    gScore.put(nextKey, tentative);
                    cameFrom.put(nextKey, current.key);
                    open.add(new Node(nextKey, tentative + heuristic(nx, ny, nz, goalX, goalY, goalZ)));
                }
            }
        }
        state.emergencyTicks = Math.min(1000, state.emergencyTicks + 1);
        return List.of();
    }

    private static List<Vec3> reconstruct(Map<Long, Long> cameFrom, long goalKey, long startKey,
                                           double cellXZ, double cellY) {
        Deque<Long> chain = new ArrayDeque<>();
        long cursor = goalKey;
        while (cursor != startKey) {
            chain.addFirst(cursor);
            Long previous = cameFrom.get(cursor);
            if (previous == null) break;
            cursor = previous;
        }
        List<Vec3> points = new ArrayList<>();
        points.add(centerOf(startKey, cellXZ, cellY));
        for (long key : chain) points.add(centerOf(key, cellXZ, cellY));
        return points;
    }

    private static List<Vec3> smooth(EntityDragonBase dragon, Level level, List<Vec3> raw) {
        if (raw.size() <= 2) return raw;
        List<Vec3> result = new ArrayList<>();
        result.add(raw.get(0));
        int cursor = 0;
        while (cursor < raw.size() - 1) {
            int far = raw.size() - 1;
            while (far > cursor + 1 && !segmentClear(dragon, level, raw.get(cursor), raw.get(far))) {
                far--;
            }
            result.add(raw.get(far));
            cursor = far;
        }
        return result;
    }

    private static boolean segmentClear(EntityDragonBase dragon, Level level, Vec3 a, Vec3 b) {
        double length = a.distanceTo(b);
        int steps = Math.max(1, (int) Math.ceil(length));
        for (int i = 1; i <= steps; i++) {
            Vec3 point = a.lerp(b, i / (double) steps);
            if (!noCollision(dragon, level, boxAt(dragon, point))) return false;
        }
        return true;
    }

    private static boolean isOpen(EntityDragonBase dragon, Level level, double cellXZ, double cellY,
                                 int x, int y, int z, DragonFlightRegistry.RuntimeState state) {
        long key = key(x, y, z);
        Boolean cached = state.occupancy.get(key);
        if (cached != null) return cached;
        Vec3 center = new Vec3((x + 0.5D) * cellXZ, (y + 0.5D) * cellY, (z + 0.5D) * cellXZ);
        BlockPos corner = BlockPos.containing(center);
        boolean open = level.hasChunkAt(corner.getX(), corner.getZ())
                && level.hasChunkAt(corner.getX() + (int) Math.ceil(cellXZ), corner.getZ() + (int) Math.ceil(cellXZ))
                && noCollision(dragon, level, boxAt(dragon, center));
        state.occupancy.put(key, open);
        return open;
    }

    private static boolean noCollision(EntityDragonBase dragon, Level level, AABB box) {
        return level.noCollision(dragon, box);
    }

    private static AABB boxAt(EntityDragonBase dragon, Vec3 center) {
        AABB box = dragon.getBoundingBox();
        return box.move(center.x - dragon.getX(), center.y - dragon.getY(), center.z - dragon.getZ());
    }

    private static Vec3 centerOf(long key, double cellXZ, double cellY) {
        return new Vec3((xOf(key) + 0.5D) * cellXZ, (yOf(key) + 0.5D) * cellY, (zOf(key) + 0.5D) * cellXZ);
    }

    private static int toCell(double value, double cell) {
        return (int) Math.floor(value / cell);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    private static int xOf(long key) {
        return unpackSigned(key, 42);
    }

    private static int yOf(long key) {
        return unpackSigned(key, 21);
    }

    private static int zOf(long key) {
        return unpackSigned(key, 0);
    }

    private static int unpackSigned(long key, int shift) {
        long bits = (key >>> shift) & 0x1FFFFFL;
        if ((bits & 0x100000L) != 0) bits -= 0x200000L;
        return (int) bits;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.sqrt((x - gx) * (x - gx) + (y - gy) * (y - gy) + (z - gz) * (z - gz));
    }

    private static int[][] offsets() {
        List<int[]> list = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    list.add(new int[]{dx, dy, dz});
                }
            }
        }
        return list.toArray(new int[0][]);
    }

    private record Node(long key, double f) {
    }
}

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

/** Incremental, server-thread-only coarse 3D A*. */
public final class DragonPathPlanner {
    public static final int GLOBAL_EXPANSIONS_PER_TICK = 1024;
    public static final int PER_DRAGON_EXPANSIONS_PER_TICK = 128;
    public static final int GLOBAL_COLLISION_CHECKS_PER_TICK = 512;
    public static final int PER_DRAGON_COLLISION_CHECKS_PER_TICK = 96;
    public static final int MAX_EXPANSIONS_PER_JOB = 4096;
    private static final int MAX_OCCUPANCY = 8192;
    private static final long TIME_BUDGET_NANOS = 3_000_000L;
    private static final int[][] OFFSETS = offsets();
    private static int remainingExpansions;
    private static int remainingCollisionChecks;
    private static long deadlineNanos;

    private DragonPathPlanner() {}

    public record Cell(int x, int y, int z) {}

    public static void beginServerTick() {
        remainingExpansions = GLOBAL_EXPANSIONS_PER_TICK;
        remainingCollisionChecks = GLOBAL_COLLISION_CHECKS_PER_TICK;
        deadlineNanos = System.nanoTime() + TIME_BUDGET_NANOS;
    }

    public static Job create(EntityDragonBase dragon, Vec3 goal) {
        if (dragon == null || goal == null || dragon.level().isClientSide() || !finite(goal)) return null;
        Level level = dragon.level();
        double cellXZ = Math.max(4.0D, Math.ceil(dragon.getBbWidth() + 1.0D));
        double cellY = Math.max(4.0D, Math.ceil(dragon.getBbHeight() + 1.0D));
        Cell start = toCell(dragon.position(), cellXZ, cellY);
        Cell end = toCell(goal, cellXZ, cellY);
        return new Job(start, end, cellXZ, cellY, goal);
    }

    /** Advances one job without allocating a new search. Returns a completed safe path, empty on failure, null while pending. */
    public static List<Vec3> advance(EntityDragonBase dragon, Job job) {
        if (dragon == null || job == null || job.finished) return job == null ? List.of() : job.result;
        if (remainingExpansions <= 0 || System.nanoTime() >= deadlineNanos) return null;
        Level level = dragon.level();
        int local = 0;
        int localCollisionChecks = 0;
        while ((job.active != null || !job.open.isEmpty()) && local < PER_DRAGON_EXPANSIONS_PER_TICK
                && job.expansions < MAX_EXPANSIONS_PER_JOB && remainingExpansions > 0
                && remainingCollisionChecks > 0 && localCollisionChecks < PER_DRAGON_COLLISION_CHECKS_PER_TICK
                && System.nanoTime() < deadlineNanos) {
            if (job.active == null) {
                Node current;
                do {
                    current = job.open.poll();
                } while (current != null && !job.closed.add(current.cell));
                if (current == null) break;
                job.active = current;
                job.nextOffset = 0;
                job.activeG = job.gScore.getOrDefault(current.cell, Double.POSITIVE_INFINITY);
                job.expansions++;
                local++;
                remainingExpansions--;
                if (current.cell.equals(job.goal)) {
                    job.finished = true;
                    job.result = reconstruct(job, current.cell);
                    return job.result;
                }
            }
            while (job.nextOffset < OFFSETS.length && remainingCollisionChecks > 0
                    && localCollisionChecks < PER_DRAGON_COLLISION_CHECKS_PER_TICK
                    && System.nanoTime() < deadlineNanos) {
                int[] offset = OFFSETS[job.nextOffset++];
                Cell next = new Cell(job.active.cell.x + offset[0], job.active.cell.y + offset[1], job.active.cell.z + offset[2]);
                remainingCollisionChecks--;
                localCollisionChecks++;
                if (!open(dragon, level, job, next)) continue;
                double horizontal = Math.hypot(offset[0], offset[2]);
                double step = Math.sqrt(horizontal * horizontal + offset[1] * offset[1]) + Math.abs(offset[1]) * 1.25D;
                double tentative = job.activeG + step;
                if (tentative < job.gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    job.gScore.put(next, tentative);
                    job.cameFrom.put(next, job.active.cell);
                    job.open.add(new Node(next, tentative + heuristic(next, job.goal)));
                }
            }
            if (job.nextOffset < OFFSETS.length) return null;
            job.active = null;
            job.nextOffset = 0;
        }
        if ((job.active == null && job.open.isEmpty()) || job.expansions >= MAX_EXPANSIONS_PER_JOB) {
            job.finished = true;
            job.result = List.of();
            return job.result;
        }
        return null;
    }

    public static final class Job {
        final Cell start;
        final Cell goal;
        final double cellXZ;
        final double cellY;
        final Vec3 requestedGoal;
        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        final Map<Cell, Double> gScore = new HashMap<>();
        final Map<Cell, Cell> cameFrom = new HashMap<>();
        final Set<Cell> closed = new HashSet<>();
        final Map<Cell, Boolean> occupancy = new HashMap<>();
        Node active;
        int nextOffset;
        double activeG;
        int expansions;
        boolean finished;
        List<Vec3> result;

        Job(Cell start, Cell goal, double cellXZ, double cellY, Vec3 requestedGoal) {
            this.start = start;
            this.goal = goal;
            this.cellXZ = cellXZ;
            this.cellY = cellY;
            this.requestedGoal = requestedGoal;
            gScore.put(start, 0.0D);
            open.add(new Node(start, heuristic(start, goal)));
        }
    }

    private static boolean open(EntityDragonBase dragon, Level level, Job job, Cell cell) {
        Boolean cached = job.occupancy.get(cell);
        if (cached != null) return cached;
        Vec3 center = center(cell, job.cellXZ, job.cellY);
        AABB box = boxAt(dragon, center);
        boolean result = finite(center)
                && center.y >= level.getMinBuildHeight() && center.y <= level.getMaxBuildHeight() - 1
                && level.getWorldBorder().isWithinBounds(BlockPos.containing(center))
                && allChunksLoaded(level, box)
                && level.noCollision(dragon, box);
        if (job.occupancy.size() < MAX_OCCUPANCY) job.occupancy.put(cell, result);
        return result;
    }

    private static boolean allChunksLoaded(Level level, AABB box) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        return level.hasChunksAt(min, max);
    }

    private static List<Vec3> reconstruct(Job job, Cell end) {
        Deque<Cell> cells = new ArrayDeque<>();
        Cell cursor = end;
        while (cursor != null && !cursor.equals(job.start)) {
            cells.addFirst(cursor);
            cursor = job.cameFrom.get(cursor);
        }
        List<Vec3> path = new ArrayList<>(cells.size() + 1);
        path.add(center(job.start, job.cellXZ, job.cellY));
        for (Cell cell : cells) path.add(center(cell, job.cellXZ, job.cellY));
        if (path.isEmpty()) return List.of();
        return List.copyOf(path);
    }

    private static Cell toCell(Vec3 value, double cellXZ, double cellY) {
        return new Cell((int) Math.floor(value.x / cellXZ), (int) Math.floor(value.y / cellY),
                (int) Math.floor(value.z / cellXZ));
    }

    private static Vec3 center(Cell cell, double cellXZ, double cellY) {
        return new Vec3((cell.x + 0.5D) * cellXZ, (cell.y + 0.5D) * cellY, (cell.z + 0.5D) * cellXZ);
    }

    private static AABB boxAt(EntityDragonBase dragon, Vec3 point) {
        return dragon.getBoundingBox().move(point.x - dragon.getX(), point.y - dragon.getY(), point.z - dragon.getZ());
    }

    private static double heuristic(Cell a, Cell b) {
        double dx = (double) a.x - b.x;
        double dy = (double) a.y - b.y;
        double dz = (double) a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static int[][] offsets() {
        List<int[]> result = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (x != 0 || y != 0 || z != 0) result.add(new int[]{x, y, z});
        return result.toArray(new int[0][]);
    }

    private record Node(Cell cell, double f) {}
}

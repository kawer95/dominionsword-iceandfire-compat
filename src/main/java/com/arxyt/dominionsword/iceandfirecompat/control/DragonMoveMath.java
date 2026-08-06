package com.arxyt.dominionsword.iceandfirecompat.control;

import net.minecraft.world.phys.Vec3;

/**
 * Pure flight/goal math shared by the autopilot and its unit tests. No entity or level access,
 * so every method is directly testable.
 */
public final class DragonMoveMath {
    public static final double AUTO_TAKEOFF_DISTANCE = 48.0D;
    public static final double AUTO_LAND_DISTANCE = 24.0D;
    public static final double CRUISE_ALTITUDE = 16.0D;
    public static final double TAKEOFF_LIFT = 12.0D;
    public static final double VERTICAL_TAKEOFF_THRESHOLD = 10.0D;
    public static final double GOAL_CHANGE_EPSILON_SQ = 1.0E-4D;
    public static final double TOP_RESERVE = 16.0D;
    public static final double BOTTOM_RESERVE = 8.0D;
    public static final double CRUISE_FLOOR_BELOW_DRAGON = 4.0D;

    public enum FlightAction {
        GROUND,
        TAKEOFF,
        CRUISE,
        LAND,
        LANDING
    }

    private DragonMoveMath() {
    }

    /** True only when a newly received goal is materially different from the stored one. */
    public static boolean goalChanged(Vec3 current, Vec3 next) {
        if (current == null) return next != null;
        if (next == null) return true;
        return current.distanceToSqr(next) > GOAL_CHANGE_EPSILON_SQ;
    }

    public static double resolveCeiling(int minBuildHeight, int maxBuildHeight) {
        return Math.max(minBuildHeight + BOTTOM_RESERVE, maxBuildHeight - TOP_RESERVE);
    }

    public static double resolveFloor(int minBuildHeight) {
        return minBuildHeight + BOTTOM_RESERVE;
    }

    public static double resolveLiftAltitude(double dragonY, int minBuildHeight, int maxBuildHeight) {
        return Math.min(resolveCeiling(minBuildHeight, maxBuildHeight),
                Math.max(resolveFloor(minBuildHeight), dragonY + TAKEOFF_LIFT));
    }

    public static double resolveCruiseAltitude(double goalY, double dragonY, int minBuildHeight, int maxBuildHeight) {
        double floor = resolveFloor(minBuildHeight);
        double desired = Math.max(goalY + CRUISE_ALTITUDE, dragonY - CRUISE_FLOOR_BELOW_DRAGON);
        return Math.min(resolveCeiling(minBuildHeight, maxBuildHeight), Math.max(floor, desired));
    }

    public static boolean shouldTakeoff(double horizontal, double vertical) {
        return horizontal >= AUTO_TAKEOFF_DISTANCE || vertical > VERTICAL_TAKEOFF_THRESHOLD;
    }

    public static boolean shouldAutoLand(double horizontal) {
        return horizontal <= AUTO_LAND_DISTANCE;
    }

    public static FlightAction decideAirborne(double horizontal, boolean auto, DragonRideState.Phase phase) {
        if (phase == DragonRideState.Phase.TAKEOFF) return FlightAction.TAKEOFF;
        if (phase == DragonRideState.Phase.LANDING) return FlightAction.LANDING;
        if (auto && shouldAutoLand(horizontal)) return FlightAction.LAND;
        return FlightAction.CRUISE;
    }

    public static FlightAction decideGrounded(double horizontal, double vertical, boolean auto,
                                              DragonRideState.Phase phase, boolean onGround) {
        if (phase == DragonRideState.Phase.LANDING && !onGround) return FlightAction.LANDING;
        if (auto && shouldTakeoff(horizontal, vertical)) return FlightAction.TAKEOFF;
        return FlightAction.GROUND;
    }
}

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
    public static final double CRUISE_SPEED = 0.8D;
    public static final double COMBAT_SPEED = 0.55D;
    public static final double STRAFE_SPEED = 1.1D;
    public static final double HOVER_SPEED = 0.05D;
    public static final double ACCELERATION = 0.06D;
    public static final double BRAKE_DECELERATION = 0.10D;
    public static final double YAW_BASE = 6.0D;
    public static final double YAW_MIN = 3.0D;
    public static final double PITCH_RATE = 3.0D;
    public static final double PITCH_LIMIT = 35.0D;

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

    public static double yawRate(int dragonStage) {
        return Math.max(YAW_MIN, YAW_BASE - 0.5D * dragonStage);
    }

    public static double approach(double current, double target, double step) {
        if (current < target) return Math.min(target, current + step);
        return Math.max(target, current - step);
    }

    public static double wrapDegrees(double value) {
        double wrapped = value % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }

    public static double approachDegrees(double current, double target, double step) {
        double delta = wrapDegrees(target - current);
        return wrapDegrees(current + Math.max(-step, Math.min(step, delta)));
    }

    /** Minecraft yaw for a horizontal direction vector. */
    public static double headingTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static double pitchTo(Vec3 dir) {
        double horizontal = Math.hypot(dir.x, dir.z);
        return Math.toDegrees(Math.atan2(-dir.y, Math.max(1.0E-4D, horizontal)));
    }

    public static double clampPitch(double pitch) {
        return Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitch));
    }

    public static Vec3 clampSpeed(Vec3 velocity, double maxSpeed) {
        double length = velocity.length();
        if (length <= maxSpeed || length <= 1.0E-6D) return velocity;
        return velocity.scale(maxSpeed / length);
    }

    /** Error-damped hover velocity (PD style) toward a desired point. */
    public static Vec3 dampedHover(Vec3 current, Vec3 desired, Vec3 currentVelocity, double maxSpeed) {
        Vec3 positionError = desired.subtract(current);
        Vec3 velocity = positionError.scale(0.08D).subtract(currentVelocity.scale(0.35D));
        return clampSpeed(velocity, maxSpeed);
    }
}

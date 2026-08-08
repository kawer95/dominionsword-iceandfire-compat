package com.arxyt.dominionsword.iceandfirecompat.control;

import net.minecraft.world.phys.Vec3;

/**
 * Pure flight/goal math shared by the autopilot and its unit tests. No entity or level access,
 * so every method is directly testable.
 */
public final class DragonMoveMath {
    public static final double AUTO_TAKEOFF_DISTANCE = 48.0D;
    public static final double AUTO_LAND_DISTANCE = 24.0D;
    public static final double CRUISE_ALTITUDE = 24.0D;
    public static final double TAKEOFF_LIFT = 24.0D;
    public static final double VERTICAL_TAKEOFF_THRESHOLD = 10.0D;
    public static final double GOAL_CHANGE_EPSILON_SQ = 1.0E-4D;
    public static final double TOP_RESERVE = 16.0D;
    public static final double BOTTOM_RESERVE = 8.0D;
    public static final double CRUISE_FLOOR_BELOW_DRAGON = 4.0D;
    public static final double CRUISE_SPEED = 1.75D;
    public static final double COMBAT_SPEED = 0.7D;
    public static final double STRAFE_SPEED = 1.1D;
    public static final double HOVER_SPEED = 0.05D;
    public static final double ACCELERATION = 0.12D;
    public static final double BRAKE_DECELERATION = 0.14D;
    public static final double YAW_BASE = 8.0D;
    public static final double YAW_MIN = 4.0D;
    public static final double PITCH_RATE = 4.0D;
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

    public static Vec3 normalizeOr(Vec3 value, Vec3 fallback) {
        if (value != null && value.lengthSqr() > 1.0E-8D) return value.normalize();
        return fallback != null && fallback.lengthSqr() > 1.0E-8D ? fallback.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
    }

    /** Rotates a direction by bounded yaw/pitch instead of replacing the velocity heading in one tick. */
    public static Vec3 turnToward(Vec3 current, Vec3 desired, double maxYawDegrees, double maxPitchDegrees) {
        Vec3 currentDir = normalizeOr(current, desired);
        Vec3 desiredDir = normalizeOr(desired, currentDir);
        double currentYaw = Math.toDegrees(Math.atan2(-currentDir.x, currentDir.z));
        double desiredYaw = Math.toDegrees(Math.atan2(-desiredDir.x, desiredDir.z));
        double yaw = approachDegrees(currentYaw, desiredYaw, maxYawDegrees);
        double currentPitch = pitchTo(currentDir);
        double desiredPitch = pitchTo(desiredDir);
        double pitch = approach(currentPitch, desiredPitch, maxPitchDegrees);
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(clampPitch(pitch));
        double horizontal = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad), Math.cos(yawRad) * horizontal);
    }

    public static double turnLimitedSpeed(double desiredSpeed, Vec3 currentDirection, Vec3 desiredDirection) {
        if (desiredSpeed <= 0.0D) return 0.0D;
        Vec3 a = normalizeOr(currentDirection, desiredDirection);
        Vec3 b = normalizeOr(desiredDirection, currentDirection);
        double dot = Math.max(-1.0D, Math.min(1.0D, a.dot(b)));
        double angle = Math.acos(dot);
        return desiredSpeed * Math.max(0.6D, Math.cos(angle * 0.5D));
    }

    /** Error-damped hover velocity (PD style) toward a desired point. */
    public static Vec3 dampedHover(Vec3 current, Vec3 desired, Vec3 currentVelocity, double maxSpeed) {
        Vec3 positionError = desired.subtract(current);
        Vec3 velocity = positionError.scale(0.08D).subtract(currentVelocity.scale(0.35D));
        return clampSpeed(velocity, maxSpeed);
    }
}

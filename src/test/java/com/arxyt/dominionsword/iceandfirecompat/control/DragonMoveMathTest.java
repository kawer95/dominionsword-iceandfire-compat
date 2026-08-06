package com.arxyt.dominionsword.iceandfirecompat.control;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonMoveMathTest {
    @Test
    void goalChangeRequiresMateriallyDifferentTarget() {
        Vec3 goal = new Vec3(100.0D, 64.0D, -200.0D);
        assertTrue(DragonMoveMath.goalChanged(null, goal));
        assertTrue(DragonMoveMath.goalChanged(goal, null));
        assertFalse(DragonMoveMath.goalChanged(goal, goal));
        assertFalse(DragonMoveMath.goalChanged(goal, goal.add(0.005D, 0.0D, 0.0D)));
        assertTrue(DragonMoveMath.goalChanged(goal, goal.add(0.02D, 0.0D, 0.0D)));
    }

    @Test
    void liftAltitudeUsesDimensionBoundsInsteadOfHardCoded128() {
        assertEquals(212.0D, DragonMoveMath.resolveLiftAltitude(200.0D, -64, 320), 1.0E-6D);
        assertEquals(304.0D, DragonMoveMath.resolveLiftAltitude(310.0D, -64, 320), 1.0E-6D);
        assertEquals(12.0D, DragonMoveMath.resolveLiftAltitude(0.0D, 0, 256), 1.0E-6D);
    }

    @Test
    void cruiseAltitudeStaysAboveDragonAndBelowWorldCeiling() {
        assertEquals(216.0D, DragonMoveMath.resolveCruiseAltitude(200.0D, 220.0D, -64, 320), 1.0E-6D);
        assertEquals(304.0D, DragonMoveMath.resolveCruiseAltitude(300.0D, 220.0D, -64, 320), 1.0E-6D);
        assertEquals(16.0D, DragonMoveMath.resolveCruiseAltitude(0.0D, -5.0D, 0, 256), 1.0E-6D);
        assertEquals(76.0D, DragonMoveMath.resolveCruiseAltitude(50.0D, 80.0D, -64, 320), 1.0E-6D);
    }

    @Test
    void autoDistanceThresholdsUseHysteresis() {
        assertTrue(DragonMoveMath.shouldTakeoff(48.0D, 0.0D));
        assertFalse(DragonMoveMath.shouldTakeoff(47.9D, 0.0D));
        assertTrue(DragonMoveMath.shouldTakeoff(0.0D, 10.1D));
        assertFalse(DragonMoveMath.shouldTakeoff(0.0D, 9.9D));
        assertTrue(DragonMoveMath.shouldAutoLand(24.0D));
        assertFalse(DragonMoveMath.shouldAutoLand(24.1D));
    }

    @Test
    void airborneDecisionFollowsTakeoffLandingCruiseSequence() {
        assertEquals(DragonMoveMath.FlightAction.TAKEOFF,
                DragonMoveMath.decideAirborne(50.0D, true, DragonRideState.Phase.TAKEOFF));
        assertEquals(DragonMoveMath.FlightAction.LANDING,
                DragonMoveMath.decideAirborne(50.0D, true, DragonRideState.Phase.LANDING));
        assertEquals(DragonMoveMath.FlightAction.LAND,
                DragonMoveMath.decideAirborne(10.0D, true, DragonRideState.Phase.CRUISE));
        assertEquals(DragonMoveMath.FlightAction.CRUISE,
                DragonMoveMath.decideAirborne(30.0D, true, DragonRideState.Phase.CRUISE));
        assertEquals(DragonMoveMath.FlightAction.CRUISE,
                DragonMoveMath.decideAirborne(30.0D, false, DragonRideState.Phase.CRUISE));
    }

    @Test
    void groundedDecisionEscalatesToFlightOnlyInAutoMode() {
        assertEquals(DragonMoveMath.FlightAction.TAKEOFF,
                DragonMoveMath.decideGrounded(50.0D, 0.0D, true, DragonRideState.Phase.GROUND, true));
        assertEquals(DragonMoveMath.FlightAction.GROUND,
                DragonMoveMath.decideGrounded(10.0D, 0.0D, true, DragonRideState.Phase.GROUND, true));
        assertEquals(DragonMoveMath.FlightAction.GROUND,
                DragonMoveMath.decideGrounded(50.0D, 0.0D, false, DragonRideState.Phase.GROUND, true));
        assertEquals(DragonMoveMath.FlightAction.LANDING,
                DragonMoveMath.decideGrounded(10.0D, 0.0D, false, DragonRideState.Phase.LANDING, false));
    }

    @Test
    void yawRateShrinksWithStageAndNeverBelowMinimum() {
        assertEquals(6.0D, DragonMoveMath.yawRate(0), 1.0E-6D);
        assertEquals(3.0D, DragonMoveMath.yawRate(6), 1.0E-6D);
        assertEquals(3.0D, DragonMoveMath.yawRate(12), 1.0E-6D);
    }

    @Test
    void angleApproachIsBoundedAndWraps() {
        assertEquals(10.0D, DragonMoveMath.approachDegrees(0.0D, 20.0D, 10.0D), 1.0E-6D);
        assertEquals(-175.0D, DragonMoveMath.approachDegrees(175.0D, -175.0D, 10.0D), 1.0E-6D);
        assertEquals(0.0D, DragonMoveMath.wrapDegrees(360.0D), 1.0E-6D);
    }

    @Test
    void hoverDampingAndSpeedClampStayBounded() {
        Vec3 clamped = DragonMoveMath.clampSpeed(new Vec3(3.0D, 0.0D, 0.0D), 0.8D);
        assertEquals(0.8D, clamped.length(), 1.0E-6D);
        Vec3 hover = DragonMoveMath.dampedHover(new Vec3(0.0D, 0.0D, 0.0D), new Vec3(5.0D, 0.0D, 0.0D),
                Vec3.ZERO, DragonMoveMath.COMBAT_SPEED);
        assertTrue(hover.length() <= DragonMoveMath.COMBAT_SPEED + 1.0E-6D);
        assertEquals(-35.0D, DragonMoveMath.clampPitch(-90.0D), 1.0E-6D);
    }
}

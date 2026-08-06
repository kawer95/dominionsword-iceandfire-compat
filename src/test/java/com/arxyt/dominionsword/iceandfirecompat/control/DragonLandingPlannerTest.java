package com.arxyt.dominionsword.iceandfirecompat.control;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonLandingPlannerTest {
    @Test
    void ringGenerationMatchesStepSampling() {
        assertEquals(1, DragonLandingPlanner.ring(0, 0, 0, DragonLandingPlanner.STEP).size());
        assertEquals(8, DragonLandingPlanner.ring(0, 0, 2, DragonLandingPlanner.STEP).size());
        assertEquals(16, DragonLandingPlanner.ring(0, 0, 4, DragonLandingPlanner.STEP).size());
        assertEquals(24, DragonLandingPlanner.ring(0, 0, 6, DragonLandingPlanner.STEP).size());
    }

    @Test
    void candidateBudgetIsMeaningfullySmallerThanFullSearchSpace() {
        int fullSpace = DragonLandingPlanner.candidateCount();
        assertTrue(fullSpace > DragonLandingPlanner.MAX_CANDIDATES);
        assertTrue(DragonLandingPlanner.MAX_READS > 0);
    }

    @Test
    void ringPositionsStayWithinRequestedRadius() {
        List<BlockPos> ring = DragonLandingPlanner.ring(10, 20, 8, DragonLandingPlanner.STEP);
        for (BlockPos pos : ring) {
            assertEquals(0, Math.max(Math.abs(pos.getX() - 10), Math.abs(pos.getZ() - 20)) % 8);
            assertTrue(Math.max(Math.abs(pos.getX() - 10), Math.abs(pos.getZ() - 20)) <= 8);
        }
    }
}

package com.arxyt.dominionsword.iceandfirecompat.control;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonRideStateCodecTest {
    @Test
    void vec3RoundTripAndClear() {
        CompoundTag state = new CompoundTag();
        Vec3 value = new Vec3(1.5D, -60.25D, 300.0D);
        DragonRideState.writeVec3(state, "goal", value);
        Vec3 read = DragonRideState.readVec3(state, "goal");
        assertEquals(value.x, read.x, 1.0E-6D);
        assertEquals(value.y, read.y, 1.0E-6D);
        assertEquals(value.z, read.z, 1.0E-6D);
        DragonRideState.clearVec3(state, "goal");
        assertFalse(state.contains("goal_x"));
    }

    @Test
    void fullCleanupRemovesEveryTransientAndTaskField() {
        CompoundTag state = populatedState();
        DragonRideState.clearStateFields(state, false);
        assertFalse(state.contains("goal_x"));
        assertFalse(state.contains("landing_x"));
        assertFalse(state.contains("landing_cooldown"));
        assertFalse(state.contains("attack"));
        assertFalse(state.contains("path_tick"));
        assertFalse(state.contains("path_fails"));
        assertFalse(state.contains("phase"));
        assertTrue(state.getBoolean("controlled"));
        assertTrue(state.hasUUID("rider"));
    }

    @Test
    void offlineTaskCleanupKeepsTaskButDropsTransientBackoff() {
        CompoundTag state = populatedState();
        DragonRideState.clearStateFields(state, true);
        assertTrue(state.contains("goal_x"));
        assertTrue(state.contains("landing_x"));
        assertTrue(state.hasUUID("attack"));
        assertFalse(state.contains("landing_cooldown"));
        assertFalse(state.contains("path_tick"));
        assertFalse(state.contains("path_fails"));
        assertFalse(state.contains("phase"));
    }

    private static CompoundTag populatedState() {
        CompoundTag state = new CompoundTag();
        state.putBoolean("controlled", true);
        state.putUUID("rider", UUID.randomUUID());
        DragonRideState.writeVec3(state, "goal", new Vec3(1.0D, 2.0D, 3.0D));
        DragonRideState.writeVec3(state, "landing", new Vec3(4.0D, 5.0D, 6.0D));
        state.putLong("landing_cooldown", 12345L);
        state.putUUID("attack", UUID.randomUUID());
        state.putInt("prev_command", 2);
        state.putLong("path_tick", 99L);
        state.putInt("path_fails", 1);
        state.putString("phase", "CRUISE");
        return state;
    }
}

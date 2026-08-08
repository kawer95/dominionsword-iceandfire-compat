package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-visible rider UUID for a Dominion-controlled dragon.
 *
 * <p>Persistent NBT ({@link DragonRideState}) stays the server-authoritative source across chunk
 * unloads; this {@link SynchedEntityData} value is what the client reads to place the designated
 * Mob rider on the saddle instead of treating it as mouth prey. It is a one-way mirror updated on
 * board, dismount and entity load.
 */
public final class DragonRiderSync {
    private static final EntityDataAccessor<Optional<UUID>> RIDER_ID =
            SynchedEntityData.defineId(EntityDragonBase.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> CONTROLLED =
            SynchedEntityData.defineId(EntityDragonBase.class, EntityDataSerializers.BOOLEAN);

    private DragonRiderSync() {
    }

    /** Touches the static field so the data key is registered at mod startup on both sides. */
    public static void ensureLoaded() {
        if (RIDER_ID == null) throw new IllegalStateException("DragonRiderSync data key unavailable");
    }

    /** Registers the key on a dragon's entity data (called from the defineSynchedData mixin). */
    public static void defineData(SynchedEntityData data) {
        if (data == null) return;
        if (!data.hasItem(RIDER_ID)) data.define(RIDER_ID, Optional.empty());
        if (!data.hasItem(CONTROLLED)) data.define(CONTROLLED, false);
    }

    /** Client-visible controlled flag so client-side native pose logic can be suppressed. */
    public static boolean getControlled(EntityDragonBase dragon) {
        return dragon != null && dragon.getEntityData().get(CONTROLLED);
    }

    public static void setControlled(EntityDragonBase dragon, boolean controlled) {
        if (dragon != null) dragon.getEntityData().set(CONTROLLED, controlled);
    }

    public static UUID getRiderId(EntityDragonBase dragon) {
        if (dragon == null) return null;
        return dragon.getEntityData().get(RIDER_ID).orElse(null);
    }

    public static void setRiderId(EntityDragonBase dragon, UUID rider) {
        if (dragon != null) {
            dragon.getEntityData().set(RIDER_ID, rider == null ? Optional.empty() : Optional.of(rider));
        }
    }
}

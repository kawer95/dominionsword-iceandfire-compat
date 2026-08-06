package com.arxyt.dominionsword.iceandfirecompat.control;

import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Single authority for dragon control permission across every command path. */
public final class DragonControlPolicy {
    private static volatile DragonControlModeSource source = () -> DragonControlMode.OWNER_ONLY;

    private DragonControlPolicy() {
    }

    /** Replaces the active mode source. Null is ignored so a broken bridge cannot lock out all dragons. */
    public static void setSource(DragonControlModeSource newSource) {
        if (newSource != null) source = newSource;
    }

    public static DragonControlMode currentMode() {
        DragonControlMode mode = source.current();
        return mode == null ? DragonControlMode.OWNER_ONLY : mode;
    }

    public static boolean allows(ServerPlayer player, EntityDragonBase dragon) {
        return player != null && allows(player.getUUID(), dragon);
    }

    public static boolean allows(UUID playerId, EntityDragonBase dragon) {
        if (playerId == null || dragon == null || dragon.isRemoved() || !dragon.isAlive() || dragon.isModelDead()) {
            return false;
        }
        return switch (currentMode()) {
            case OWNER_ONLY -> dragon.isTame() && dragon.getOwnerUUID() != null && dragon.getOwnerUUID().equals(playerId);
            case TAMED_ONLY -> dragon.isTame();
            case ALL -> true;
        };
    }
}

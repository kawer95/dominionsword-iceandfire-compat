package com.arxyt.dominionsword.iceandfirecompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionSkills;
import com.arxyt.dominionsword.control.PlayerControl;
import com.arxyt.dominionsword.config.ServerConfig;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonFlightRegistry;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlModeSource;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlPolicy;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlMode;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRiderSync;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.UUID;

@Mod(DominionSwordIceAndFireCompatMod.MODID)
public final class DominionSwordIceAndFireCompatMod {
    public static final String MODID = "dominionsword_iceandfire_compat";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DominionSwordIceAndFireCompatMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
    }

    /** Binds a server-configurable control mode source once the main mod exposes one. */
    public static void setDragonControlModeSource(DragonControlModeSource source) {
        DragonControlPolicy.setSource(source);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!ModList.get().isLoaded("iceandfire")) {
            LOGGER.warn("[DominionSword Ice And Fire Compat] iceandfire is not loaded; dragon bridge disabled.");
            return;
        }
        DragonRiderSync.ensureLoaded();
        // The main mod owns the persisted server config; this bridge keeps every adapter entry
        // on the same live policy without exposing Ice and Fire classes to the main mod.
        setDragonControlModeSource(() -> switch (ServerConfig.ICE_AND_FIRE_DRAGON_CONTROL.get()) {
            case "tamed_only" -> DragonControlMode.TAMED_ONLY;
            case "all" -> DragonControlMode.ALL;
            default -> DragonControlMode.OWNER_ONLY;
        });
        event.enqueueWork(() -> {
            DominionControlApi.registerVehicleAdapter(new IceAndFireDragonVehicleAdapter());
            DominionSkills.register(new IceAndFireDragonSkillProvider());
            String iafVersion = ModList.get().getModContainerById("iceandfire")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            LOGGER.info("[DominionSword Ice And Fire Compat] dragon vehicle bridge enabled; iceandfire version {}", iafVersion);
        });
    }

    public static final class ServerEvents {
        private ServerEvents() {
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) DragonFlightRegistry.beginServerTick(event.getServer());
            if (event.phase == TickEvent.Phase.END) DragonAutopilot.tick(event.getServer());
        }

        @SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide()) return;
            if (!(event.getEntity() instanceof EntityDragonBase dragon)) return;
            if (!DragonRideState.isControlled(dragon)) return;
            UUID controller = PlayerControl.controller(dragon);
            if (controller == null || !DragonControlPolicy.allows(controller, dragon)) {
                DragonAutopilot.forceEndControl(dragon);
                return;
            }
            // Mirror the persisted rider/controlled markers into synced entity data so the
            // client renders the designated rider on the saddle from the very first tick.
            UUID rider = DragonRideState.riderId(dragon);
            if (rider != null) DragonRiderSync.setRiderId(dragon, rider);
            DragonRiderSync.setControlled(dragon, true);
            // Never force-end control here.  During a world reload the dragon joins before the
            // owning player does, and forceEndControl() clears both the controlled flag and the
            // rider marker, handing a still-mounted rider back to Ice And Fire's native
            // prey-in-mouth pipeline (SHAKEPREY bite).  Ownership is validated online by
            // DragonAutopilot.tick once the player is present; offline persistent tasks are
            // intentionally allowed to keep running.
        }

        @SubscribeEvent
        public static void onEntityLeave(EntityLeaveLevelEvent event) {
            if (event.getEntity() instanceof EntityDragonBase dragon) DragonFlightRegistry.remove(dragon);
        }

        @SubscribeEvent
        public static void onBlockChanged(BlockEvent event) {
            if (event.getLevel() instanceof ServerLevel level) DragonFlightRegistry.invalidateCorridors(level.dimension());
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            DragonFlightRegistry.clear();
        }
    }
}

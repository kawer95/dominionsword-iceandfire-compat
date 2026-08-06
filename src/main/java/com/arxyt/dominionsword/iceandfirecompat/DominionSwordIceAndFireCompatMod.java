package com.arxyt.dominionsword.iceandfirecompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionSkills;
import com.arxyt.dominionsword.control.PlayerControl;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlModeSource;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlPolicy;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRiderSync;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
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
            if (event.phase == TickEvent.Phase.END) DragonAutopilot.tick(event.getServer());
        }

        @SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide()) return;
            if (!(event.getEntity() instanceof EntityDragonBase dragon)) return;
            if (DragonRideState.isControlled(dragon)) {
                UUID rider = DragonRideState.riderId(dragon);
                if (rider != null) DragonRiderSync.setRiderId(dragon, rider);
            } else {
                return;
            }
            UUID owner = PlayerControl.controller(dragon);
            boolean validOwner = false;
            if (owner != null && event.getLevel() instanceof ServerLevel level) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
                validOwner = player != null && PlayerControl.ids(player).contains(dragon.getUUID());
            }
            if (!validOwner) DragonAutopilot.endControl(dragon);
        }
    }
}

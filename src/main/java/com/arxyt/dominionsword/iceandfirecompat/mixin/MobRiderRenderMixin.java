package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.render.entity.layer.LayerDragonRider;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * The dragon rider layer renders the registered Dominion rider on the model body (glued to the
 * neck), but Ice And Fire only suppresses the normal entity render for PLAYER riders (its
 * PlayerEntityRendererMixin).  A Mob unit rider would therefore draw twice: once on the model
 * body following the animation, and once at its entity position (static).  Cancel the standalone
 * world render of the registered rider so only the model-attached copy shows, exactly like a
 * player.
 *
 * <p>This must be done at the dispatcher level rather than on MobRenderer: the maid mod's
 * EntityMaidRenderer overrides render(), so an injection into MobRenderer's method never runs for
 * the maid.  Ice And Fire's rider layer draws the passenger through the same dispatcher while the
 * passenger is on {@link LayerDragonRider#renderingRiders}; that call must keep running, so the
 * guard below skips cancellation while the entity is on that list.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MobRiderRenderMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void dominionsword$skipStandaloneRiderRender(Entity entity, double x, double y, double z, float yaw,
                                                         float partialTicks, PoseStack poseStack,
                                                         MultiBufferSource buffer, int packedLight,
                                                         CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living)) return;
        if (!(living.getVehicle() instanceof EntityDragonBase dragon)) return;
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId == null || !riderId.equals(entity.getUUID())) return;
        if (LayerDragonRider.renderingRiders.contains(entity)) return;
        ci.cancel();
    }
}

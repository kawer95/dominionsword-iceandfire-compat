package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.render.entity.layer.LayerDragonRider;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * The native rider layer draws every non-controlling passenger at the neck/head (mouth) slot,
 * while the controlling passenger is drawn on the BODY attached to the model animation (and the
 * normal entity render is suppressed via the layer's renderingRiders list).
 *
 * <p>The registered Dominion rider (a Mob unit) is not a controlling passenger, so natively it
 * would render at the mouth and, worse, its own entity render at the saddle would NOT follow the
 * model's neck animation.  Redirect the layer's controlling-passenger lookup to the registered
 * rider so it is drawn on the model body exactly like a player rider: glued to the neck and
 * following its up-and-down motion.  Other passengers (grabbed prey, players) keep native
 * behaviour.
 */
@Mixin(LayerDragonRider.class)
public abstract class LayerDragonRiderMixin {
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/iafenvoy/iceandfire/entity/EntityDragonBase;m_6688_()Lnet/minecraft/world/entity/LivingEntity;", remap = false),
            remap = false)
    private LivingEntity dominionsword$registeredRiderAsControlling(EntityDragonBase dragon) {
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId != null) {
            for (Entity passenger : dragon.getPassengers()) {
                if (passenger.getUUID().equals(riderId) && passenger instanceof LivingEntity living) {
                    return living;
                }
            }
        }
        return dragon.getControllingPassenger();
    }

    /**
     * The layer rotates the rider by its own yaw (riderRot) and the rider's own renderer derives
     * its body yaw from the vehicle body plus the rider's head yaw (vanilla rider logic, clamped
     * to +/-85 degrees).  A Mob rider's yaw fields are frozen at mount time, so when the dragon
     * turns the model stays looking at the old world direction.  Mirror the dragon's HEAD yaw into
     * the registered rider's yaw fields here, right before the layer consumes them, so both the
     * layer rotation and the rider's own renderer keep the model facing the dragon's head.  Players
     * and grabbed prey keep their native orientation.
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At("HEAD"), remap = false)
    private void dominionsword$alignRiderYawToDragonHead(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                         EntityDragonBase dragon, float limbSwing, float limbSwingAmount,
                                                         float partialTicks, float ageInTicks, float netHeadYaw,
                                                         float headPitch, CallbackInfo ci) {
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId == null) return;
        for (Entity passenger : dragon.getPassengers()) {
            if (!passenger.getUUID().equals(riderId) || !(passenger instanceof LivingEntity living)) continue;
            float headYaw = dragon.getYHeadRot();
            float prevHeadYaw = dragon.yHeadRotO;
            living.setYRot(headYaw);
            living.yRotO = prevHeadYaw;
            living.setYHeadRot(headYaw);
            living.yHeadRotO = prevHeadYaw;
            living.setYBodyRot(dragon.yBodyRot);
            living.yBodyRotO = dragon.yBodyRotO;
            return;
        }
    }
}

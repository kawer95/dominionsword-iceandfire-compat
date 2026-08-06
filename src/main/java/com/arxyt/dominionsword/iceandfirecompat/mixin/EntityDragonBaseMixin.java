package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRiderSync;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Dragon-side hooks for Dominion control:
 *
 * <ul>
 *   <li>Designated Mob riders sit on the saddle position instead of becoming prey in the mouth.</li>
 *   <li>Controlled dragons never trigger their native random takeoff.</li>
 *   <li>Controlled dragons only accept the commander's commanded target.</li>
 * </ul>
 *
 * <p>Method names below are the official mappings and their SRG counterparts verified against the
 * released Ice And Fire CE 1.2.7 Forge jar; both are listed so the mixin applies in dev and in the
 * reobfuscated production jar.
 */
@Mixin(EntityDragonBase.class)
public abstract class EntityDragonBaseMixin {
    @Inject(
            method = {
                    "defineSynchedData()V",
                    "m_8097_()V"
            },
            at = @At("TAIL"),
            remap = false
    )
    private void dominionsword$registerRiderSyncData(CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        DragonRiderSync.defineData(dragon.getEntityData());
    }

    @Inject(
            method = {
                    "updatePassengerPosition(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
                    "m_19956_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V"
            },
            at = @At("HEAD"),
            remap = false,
            cancellable = true
    )
    private void dominionsword$positionDesignatedRider(Entity passenger, Entity.MoveFunction positionUpdater, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId == null || !riderId.equals(passenger.getUUID()) || passenger.getVehicle() != dragon) return;
        if (dragon.isModelDead() || !dragon.isAlive()) {
            passenger.stopRiding();
            DragonRideState.clearRiderMarkers(dragon, passenger instanceof Mob mob ? mob : null);
            ci.cancel();
            return;
        }
        Vec3 pos = dragon.getRiderPosition();
        passenger.setPos(pos.x, pos.y + passenger.getBbHeight(), pos.z);
        ci.cancel();
    }

    @Inject(method = "isAllowedToTriggerFlight", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockRandomFlight(CallbackInfoReturnable<Boolean> cir) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (DragonRideState.isControlled(dragon)) cir.setReturnValue(false);
    }

    @Inject(method = "doesWantToLand", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockNativeLanding(CallbackInfoReturnable<Boolean> cir) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (DragonRideState.isControlled(dragon)) cir.setReturnValue(false);
    }

    @Inject(method = "breakBlocks(Z)V", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockTerrainBreaking(boolean force, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (DragonRideState.isControlled(dragon)) ci.cancel();
    }

    @Inject(
            method = {
                    "setTarget(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "m_6710_(Lnet/minecraft/world/entity/LivingEntity;)V"
            },
            at = @At("HEAD"),
            remap = false,
            cancellable = true
    )
    private void dominionsword$gateTarget(net.minecraft.world.entity.LivingEntity target, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (!DragonRideState.isControlled(dragon)) return;
        UUID commanded = DragonRideState.attackTarget(dragon);
        if (target == null) {
            return;
        }
        if (commanded == null || !commanded.equals(target.getUUID())) ci.cancel();
    }
}

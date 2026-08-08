package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRiderSync;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.nbt.CompoundTag;
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
    /**
     * Ice and Fire's AI goals run inside this step.  For a controlled dragon they are never
     * allowed to pick follow/flight/wander destinations; the Dominion controller below is the
     * only thing that decides where the dragon goes.
     */
    @Inject(method = "m_8024_()V", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$cancelNativeAi(CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (DragonRideState.isControlled(dragon)) ci.cancel();
    }

    /**
     * Dominion runs at the very end of the dragon tick.  Ice and Fire still mutates flight
     * flags and velocity in several places after the AI step (aiStep tail, server logic, flight
     * manager); a HEAD-of-AI controller is always overwritten by those.  Writing last makes
     * {@link DragonFlightController} the sole actuator every tick.
     */
    @Inject(method = "m_8119_()V", at = @At("TAIL"), remap = false)
    private void dominionsword$applyDominionControl(CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (DragonRideState.isControlled(dragon)) DragonAutopilot.tickFlight(dragon);
    }

    @Inject(
            method = "m_8097_()V",
            at = @At("TAIL"),
            remap = false
    )
    private void dominionsword$registerRiderSyncData(CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        DragonRiderSync.defineData(dragon.getEntityData());
    }

    /** Synched entity data is not restored from NBT automatically.  After a world reload the
     *  client would see an empty rider marker, so the designated rider falls back to native
     *  prey-in-mouth rendering until it dismounts and boards again.  Mirror the persistent
     *  markers back into the entity data on load so the client is correct from the first tick. */
    @Inject(
            method = "m_7378_(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("TAIL"),
            remap = false
    )
    private void dominionsword$restoreRiderSyncAfterLoad(CompoundTag tag, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        if (dragon.level().isClientSide()) return;
        DragonRiderSync.setRiderId(dragon, DragonRideState.riderId(dragon));
        DragonRiderSync.setControlled(dragon, DragonRideState.isControlled(dragon));
    }

    @Inject(
            method = "m_19956_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"),
            remap = false,
            cancellable = true
    )
    private void dominionsword$positionDesignatedRider(Entity passenger, Entity.MoveFunction positionUpdater, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        UUID riderId = DragonRideState.riderId(dragon);
        boolean isRider = riderId != null && riderId.equals(passenger.getUUID());
        boolean vehicleOk = passenger.getVehicle() == dragon;
        if (!isRider || !vehicleOk) return;
        if (dragon.isModelDead() || !dragon.isAlive()) {
            passenger.stopRiding();
            DragonRideState.clearRiderMarkers(dragon, passenger instanceof Mob mob ? mob : null);
            ci.cancel();
            return;
        }
        // Exactly like the native player rider path: position at getRiderPosition() (the neck
        // saddle) so the unit sits on the neck and follows its up-and-down motion with the
        // dragon's pitch, instead of being clamped onto the body.
        Vec3 riderPos = dragon.getRiderPosition();
        passenger.setPos(riderPos.x, riderPos.y + passenger.getBbHeight(), riderPos.z);
        ci.cancel();
    }

    /** Native dragon AI grabs every non-controlling passenger as mouth prey (SHAKEPREY).  A
     *  designated Mob rider must never be grabbed, shaken or bitten, so cancel the prey update
     *  whenever the target is the registered rider. */
    /** Only the registered driver is protected from the mouth-prey pipeline.  The method starts
     *  the shake-prey animation, positions the victim in the mouth and deals bite damage; it is
     *  cancelled exclusively when the victim is the designated rider.  Grabbing and biting other
     *  units must keep working, so no blanket controlled-dragon suppression is applied. */
    @Inject(method = "updatePreyInMouth(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$protectDesignatedRider(Entity prey, CallbackInfo ci) {
        EntityDragonBase dragon = (EntityDragonBase) (Object) this;
        // The registered rider marker is the protection key.  Other passengers (grabbed prey)
        // keep native shake/bite behaviour.
        UUID riderId = DragonRideState.riderId(dragon);
        if (prey != null && riderId != null && riderId.equals(prey.getUUID())
                && prey.getVehicle() == dragon && dragon.getPassengers().contains(prey)) {
            ci.cancel();
        }
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
            method = "m_6710_(Lnet/minecraft/world/entity/LivingEntity;)V",
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

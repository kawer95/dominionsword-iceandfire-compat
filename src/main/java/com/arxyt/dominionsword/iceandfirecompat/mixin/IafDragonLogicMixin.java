package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRiderSync;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.entity.util.dragon.IafDragonLogic;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * The native server-side dragon state machine runs from {@code EntityDragonBase.tick()} after
 * the AI step and flips flying/hovering plus writes vertical velocity every tick.  Dominion
 * replaces that state machine while a dragon is controlled.
 */
@Mixin(IafDragonLogic.class)
public abstract class IafDragonLogicMixin {
    @Shadow(remap = false)
    private EntityDragonBase dragon;

    @Inject(method = "updateDragonServer", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$suppressNativeServerState(CallbackInfo ci) {
        if (dragon != null && DragonRideState.isControlled(dragon)) ci.cancel();
    }

    /**
     * Client-side pose guard: the native flap buffer derives the body roll from yaw/head
     * differences and, under Dominion's server-driven yaw, never settles back to level.
     * Reset the roll buffer every client tick so a controlled dragon's body stays level.
     */
    /**
     * The native logic attack entry point (tackle, bite, tail-whip, wing-blast and rider
     * attacks all end here).  A registered rider must never be damaged by its own dragon;
     * anything else keeps the native attack untouched.
     */
    @Inject(method = "attackTarget", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$protectRiderFromLogicAttack(Entity target, Player ridingPlayer, float damage,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (target == null || dragon == null) return;
        UUID riderId = DragonRideState.riderId(dragon);
        if (riderId != null && riderId.equals(target.getUUID()) && target.getVehicle() == dragon
                && dragon.getPassengers().contains(target)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateDragonClient", at = @At("TAIL"), remap = false)
    private void dominionsword$levelControlledBody(CallbackInfo ci) {
        if (dragon != null && DragonRiderSync.getControlled(dragon)) {
            if (dragon.roll_buffer != null) dragon.roll_buffer.resetRotations();
        }
    }

    /** The native wing-flap sound is played from the client-side common logic.  Under Dominion
     *  the controller re-emits it server-side (single source for the command-camera bridge), so
     *  skip the client copy for controlled dragons; uncontrolled dragons keep the native play. */
    @Redirect(method = "updateDragonCommon()V",
            at = @At(value = "INVOKE", target = "Lcom/iafenvoy/iceandfire/entity/EntityDragonBase;m_5496_(Lnet/minecraft/sounds/SoundEvent;FF)V", remap = false),
            remap = false)
    private void dominionsword$suppressClientFlapSound(EntityDragonBase dragon, net.minecraft.sounds.SoundEvent sound,
                                                       float volume, float pitch) {
        if (dragon != null && !DragonRiderSync.getControlled(dragon)) {
            dragon.playSound(sound, volume, pitch);
        }
    }
}

package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Last-line guard for Ice And Fire's native bite pipeline.  The native dragon treats every
 * non-controlling passenger as mouth prey: {@code updatePassengerPosition} funnels them into
 * {@code updatePreyInMouth}, which sets SHAKEPREY, bites after 55 ticks and force-dismounts.
 * The melee logic ({@code tryAttack}, {@code IafDragonLogic#attackTarget}) can also reach the
 * rider if the entity ever becomes the dragon's target.
 *
 * <p>The only entity a dragon must never bite is its own registered Dominion rider.  This guard
 * cancels damage exactly when the direct attacker is a dragon and the victim is that dragon's
 * registered rider.  Bites against every other unit, grabbed prey or melee target, stay native.
 */
@Mixin(Entity.class)
public abstract class EntityDamageMixin {
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void dominionsword$protectRegisteredRider(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() || source == null) return;
        Entity attacker = source.getDirectEntity();
        if (!(attacker instanceof EntityDragonBase dragon)) return;
        // The registered rider marker is the protection key; it survives control-end, force-end
        // and reloads (see DragonRideState.clearControlState), so the rider is always covered.
        // Other passengers (e.g. grabbed SHAKEPREY prey) are deliberately NOT protected: melee
        // bites on other units must keep working.
        UUID riderId = DragonRideState.riderId(dragon);
        // A persisted UUID alone is not sufficient: external dismounts and entity-load ordering
        // can leave a marker briefly stale.  Protection applies only while this victim is an
        // actual passenger of the attacking dragon.
        if (riderId != null && riderId.equals(self.getUUID()) && self.getVehicle() == dragon
                && dragon.getPassengers().contains(self)) {
            cir.setReturnValue(false);
        }
    }
}

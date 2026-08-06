package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.entity.ai.DragonAIReturnToRoost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A controlled dragon never fights the commander by flying back to its roost. */
@Mixin(DragonAIReturnToRoost.class)
public abstract class DragonAIReturnToRoostMixin {
    @Shadow(remap = false)
    private EntityDragonBase dragon;

    @Inject(method = {"canUse", "m_8036_"}, at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockReturnToRoostWhileControlled(CallbackInfoReturnable<Boolean> cir) {
        if (dragon != null && DragonRideState.isControlled(dragon)) cir.setReturnValue(false);
    }
}

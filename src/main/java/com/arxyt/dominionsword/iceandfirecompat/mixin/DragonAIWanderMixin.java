package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.entity.ai.DragonAIWander;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A controlled dragon holds position instead of wandering between orders. */
@Mixin(DragonAIWander.class)
public abstract class DragonAIWanderMixin {
    @Shadow(remap = false)
    private EntityDragonBase dragon;

    @Inject(method = {"canUse", "m_8036_"}, at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockWanderWhileControlled(CallbackInfoReturnable<Boolean> cir) {
        if (dragon != null && DragonRideState.isControlled(dragon)) cir.setReturnValue(false);
    }
}

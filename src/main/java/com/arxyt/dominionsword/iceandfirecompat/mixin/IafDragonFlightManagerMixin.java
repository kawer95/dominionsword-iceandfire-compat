package com.arxyt.dominionsword.iceandfirecompat.mixin;

import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.iafenvoy.iceandfire.entity.util.dragon.IafDragonFlightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Neutralizes the native random flight-target generator and its +0.1 upward velocity for
 * Dominion-controlled dragons; the new controller owns airborne motion.
 */
@Mixin(IafDragonFlightManager.class)
public abstract class IafDragonFlightManagerMixin {
    @Shadow(remap = false)
    private EntityDragonBase dragon;

    @Inject(method = "update", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$blockNativeFlightTargets(CallbackInfo ci) {
        if (dragon != null && DragonRideState.isControlled(dragon)) ci.cancel();
    }
}

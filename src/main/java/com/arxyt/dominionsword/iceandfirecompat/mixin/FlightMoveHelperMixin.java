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
 * Replaces the native FlightMoveHelper with {@link DragonFlightController} for controlled dragons,
 * keeping the correct move-control tick phase while suppressing the native collision 180° flip and
 * straight-line acceleration.
 */
@Mixin(IafDragonFlightManager.FlightMoveHelper.class)
public abstract class FlightMoveHelperMixin {
    @Shadow(remap = false)
    private EntityDragonBase dragon;

    @Inject(method = "m_8126_()V", at = @At("HEAD"), remap = false, cancellable = true)
    private void dominionsword$controlFlight(CallbackInfo ci) {
        if (dragon != null && DragonRideState.isControlled(dragon)) {
            ci.cancel();
        }
    }
}

package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S4b — anonymous body answers held-v2, outer bytes unchanged.
 *
 * <p>The outer class file is byte-identical to S4a (sha256-asserted in the driver):
 * the only change is the body of the anonymous class that the long-lived field
 * already holds. {@code poke=held-v2} = the live instance was redefined in place,
 * {@code poke=held-v1} = the old class survived as an orphan.</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        if (AnonTarget.HELD4 == null) {
            AnonTarget.HELD4 = new Supplier<String>() {
                @Override
                public String get() {
                    return "held-v2";
                }
            };
        }
        cir.setReturnValue(AnonTarget.HELD4.get());
    }
}

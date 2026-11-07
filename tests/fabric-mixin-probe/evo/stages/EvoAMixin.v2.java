package com.example.evo.mixin;

import com.example.evo.EvoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario A, v2: the same HEAD hook becomes {@code cancellable = true} and
 * forces a return value. Mixin must (re)generate the conditional-return code in
 * {@code EvoTarget.fire()} and the hot swap must install it.
 */
@Mixin(EvoTarget.class)
public class EvoAMixin {
    @Inject(method = "fire", at = @At("HEAD"), cancellable = true)
    private void evoA$head(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-A] v2 head cancellable");
        cir.setReturnValue("CANCELLED");
    }
}

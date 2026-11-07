package com.example.evo.mixin;

import com.example.evo.EvoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario A, v1: plain HEAD hook, not cancellable. The target must keep
 * returning its original value.
 */
@Mixin(EvoTarget.class)
public class EvoAMixin {
    @Inject(method = "fire", at = @At("HEAD"))
    private void evoA$head(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-A] v1 head");
    }
}

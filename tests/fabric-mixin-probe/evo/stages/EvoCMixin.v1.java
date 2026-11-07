package com.example.evo.mixin;

import com.example.evo.EvoLifecycleTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario C, v1: exactly one {@code @Inject} handler method. A hot update adds
 * a second handler method to this Mixin class; a later update removes it again.
 */
@Mixin(EvoLifecycleTarget.class)
public class EvoCMixin {
    @Inject(method = "fire", at = @At("HEAD"))
    private void evoCHookOne(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-C] hookOne");
    }
}

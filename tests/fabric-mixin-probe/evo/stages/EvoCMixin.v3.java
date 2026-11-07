package com.example.evo.mixin;

import com.example.evo.EvoLifecycleTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario C, v3: the added handler method is removed again. The target class
 * must be re-woven from the un-woven bytes so hookTwo disappears (both the
 * injected calls and the copied handler method).
 */
@Mixin(EvoLifecycleTarget.class)
public class EvoCMixin {
    @Inject(method = "fire", at = @At("HEAD"))
    private void evoCHookOne(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-C] hookOne");
    }
}

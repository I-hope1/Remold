package com.example.evo.mixin;

import com.example.evo.EvoLifecycleTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario C, v2: a second {@code @Inject} handler method is added to the Mixin
 * class itself. This is a structural change to the Mixin template: Mixin's
 * ClassInfo has to learn the new method (MixinClassInfoSync) and the target
 * class must be re-woven and structurally redefined with both hooks.
 */
@Mixin(EvoLifecycleTarget.class)
public class EvoCMixin {
    @Inject(method = "fire", at = @At("HEAD"))
    private void evoCHookOne(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-C] hookOne");
    }

    @Inject(method = "fire", at = @At("HEAD"))
    private void evoCHookTwo(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-C] hookTwo");
    }
}

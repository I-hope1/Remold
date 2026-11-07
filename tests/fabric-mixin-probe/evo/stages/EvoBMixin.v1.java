package com.example.evo.mixin;

import com.example.evo.EvoSwitchTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario B, v1: hook on {@code methodOne}. The hot update moves the hook to
 * {@code methodTwo}; {@code methodOne} must be restored byte-for-byte.
 */
@Mixin(EvoSwitchTarget.class)
public class EvoBMixin {
    @Inject(method = "methodOne", at = @At("HEAD"))
    private void evoB$one(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-B] one v1");
    }
}

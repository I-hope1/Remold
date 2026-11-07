package com.example.evo.mixin;

import com.example.evo.EvoSwitchTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario B, v2: the hook moves to {@code methodTwo}. The hot swap must weave
 * the new target method and restore {@code methodOne} from the un-woven class
 * bytes (no stale handler left behind).
 */
@Mixin(EvoSwitchTarget.class)
public class EvoBMixin {
    @Inject(method = "methodTwo", at = @At("HEAD"))
    private void evoB$two(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-B] two v2");
    }
}

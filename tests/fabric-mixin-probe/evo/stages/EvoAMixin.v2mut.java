package com.example.evo.mixin;

import com.example.evo.EvoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scenario A mutation control: the hot update still happens (new class bytes,
 * new v2 print) but {@code cancellable} is reverted and no forced return is
 * produced. The "returns CANCELLED" / "applied bytes contain isCancelled"
 * assertions must go red, proving they measure the cancellable evolution.
 */
@Mixin(EvoTarget.class)
public class EvoAMixin {
    @Inject(method = "fire", at = @At("HEAD"))
    private void evoA$head(CallbackInfoReturnable<String> cir) {
        System.out.println("[EVO-A] v2 head (mutation: cancellable reverted)");
    }
}

package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S2 (R5 red) — the anonymous body returns anon-v2; the driver asserts the outer
 * AnonTargetMixin.class bytes are unchanged versus S1b.
 * Implemented so that the outer class file is byte-identical between s1b and s2:
 * only the anonymous class body differs (anon-v1 -> anon-v2).
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        Supplier<String> part = new Supplier<String>() {
            @Override
            public String get() {
                return "anon-v2";
            }
        };
        String value = part.get();
        cir.setReturnValue(value);
    }
}

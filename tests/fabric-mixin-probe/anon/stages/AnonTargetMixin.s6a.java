package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S6a — baseline for the renumbering stage: ONE anonymous class, allocated
 * once and held by {@code AnonTarget.HELD6} (body {@code s6-v1}).
 *
 * <p>The baseline is a mandatory precondition: without {@code poke=s6-v1} before the
 * renumbering, a later unchanged value would prove nothing.</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        if (AnonTarget.HELD6 == null) {
            AnonTarget.HELD6 = new Supplier<String>() {
                @Override
                public String get() {
                    return "s6-v1";
                }
            };
        }
        cir.setReturnValue(AnonTarget.HELD6.get());
    }
}

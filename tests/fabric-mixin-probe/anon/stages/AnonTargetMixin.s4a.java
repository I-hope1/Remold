package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S4a — long-lived field holding an anonymous-class instance.
 *
 * <p>The handler allocates the anonymous instance exactly ONCE and stores it in
 * {@code AnonTarget.HELD4}, so every later read (including S4b's) goes through the
 * SAME live object; S4b keeps these outer bytes byte-identical and changes only the
 * anonymous body, so the field read proves in-place redefinition.</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        if (AnonTarget.HELD4 == null) {
            AnonTarget.HELD4 = new Supplier<String>() {
                @Override
                public String get() {
                    return "held-v1";
                }
            };
        }
        cir.setReturnValue(AnonTarget.HELD4.get());
    }
}

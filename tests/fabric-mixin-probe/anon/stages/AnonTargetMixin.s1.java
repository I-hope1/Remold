package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S1 (R3 red stage): the @Inject handler gains its FIRST anonymous class, which
 * javac compiles into a separate class file {@code AnonTargetMixin$1.class}.
 * The handler returns whatever the anonymous class produces ({@code anon-v1}).
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        Supplier<String> part = new Supplier<String>() {
            @Override
            public String get() {
                return "anon-v1";
            }
        };
        cir.setReturnValue(part.get());
    }
}

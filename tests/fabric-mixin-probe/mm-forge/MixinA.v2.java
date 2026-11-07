package com.example.examplemod.mixin.a;

import com.example.examplemod.multi.MultiTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiTarget.class, priority = 900)
public class MixinA {
    @Unique private int uniqueA = 10;
    @Unique private int sharedNameTag = 100;

    @Inject(method = "fire", at = @At("HEAD"))
    private void aHead(CallbackInfoReturnable<String> cir) {
        System.out.println("[MM-A] v2 uniqueA=" + uniqueA + " shared=" + sharedNameTag);
    }
}

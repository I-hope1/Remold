package com.example.examplemod.mixin.b;

import com.example.examplemod.multi.MultiTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiTarget.class, priority = 1100)
public class MixinB {
    @Unique private int uniqueB = 20;
    @Unique private int sharedNameTag = 200;

    @Inject(method = "fire", at = @At("HEAD"))
    private void bHead(CallbackInfoReturnable<String> cir) {
        System.out.println("[MM-B] v2 uniqueB=" + uniqueB + " shared=" + sharedNameTag);
    }
}

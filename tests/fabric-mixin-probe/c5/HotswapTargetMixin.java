package com.example.examplemod.mixin;

import com.example.examplemod.HotswapTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** C5 Forge Mixin target: the class that Remold hot-swaps is also a Mixin target. */
@Mixin(HotswapTarget.class)
public abstract class HotswapTargetMixin {
    @Unique
    private int c5Unique = 123;

    @Inject(method = "onReload", at = @At("HEAD"))
    private void c5Inject(CallbackInfo ci) {
        System.out.println("[C5-MIXIN] onReload unique=" + this.c5Unique);
    }
}

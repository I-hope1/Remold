package ciprobe.mixin;

import ciprobe.ClassInfoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that exists as a compiled class at startup but is deliberately absent from
 * {@code ci.mixins.json}. The config-diff probe adds it at runtime; its handler must appear on the
 * live target without a restart.
 */
@Mixin(ClassInfoTarget.class)
public abstract class ConfigNewMixin {
    @Inject(method = "onReload", at = @At("HEAD"))
    private void ciConfigInject(CallbackInfo ci) {
        ClassInfoTarget.hits4++;
    }
}

package neprobe.mixin;

import neprobe.ProbeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Compiled at startup, deliberately absent from the initial config; added by the config diff. */
@Mixin(ProbeTarget.class)
public abstract class ConfigNewMixin {
    @Inject(method = "onReload", at = @At("HEAD"))
    private void npConfigInject(CallbackInfoReturnable<String> cir) {
        ProbeTarget.hits4++;
    }
}

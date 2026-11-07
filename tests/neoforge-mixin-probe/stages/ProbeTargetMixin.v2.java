package neprobe.mixin;

import neprobe.ProbeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin v2 — stage 1 of the NeoForge probe: a <b>new {@code @Inject} handler method</b>
 * ({@code npInject2}) added to the template.
 *
 * <p>Without ClassInfo member sync the reload validation cannot find this method in the stale
 * {@code ClassInfo} and the hot update is rejected ({@code Conform error ... method is null});
 * with the sync the handler is woven and {@code hits2} starts incrementing.</p>
 */
@Mixin(ProbeTarget.class)
public abstract class ProbeTargetMixin {
    @Unique
    private int uniqueA = 123;

    @Inject(method = "onReload", at = @At("HEAD"))
    private void npInject1(CallbackInfoReturnable<String> cir) {
        ProbeTarget.hits1++;
        ProbeTarget.seen1 = this.uniqueA;
    }

    @Inject(method = "onReload", at = @At("HEAD"))
    private void npInject2(CallbackInfoReturnable<String> cir) {
        ProbeTarget.hits2++;
    }
}

package neprobe.mixin;

import neprobe.ProbeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Second Mixin on the same target, baseline version (stage 2 fixture).
 *
 * <p>It exists so the stage 2 probe (a <b>new {@code @Unique} field</b>) is a change to a
 * <em>different</em> template than stage 1. The Forge/NeoForge bridge reports a failed template
 * reload once per template class, so using one template for both stages would hide the second
 * attempt from the log and make the run unattributable.</p>
 */
@Mixin(ProbeTarget.class)
public abstract class ProbeTargetSecondMixin {
    @Inject(method = "onReload", at = @At("HEAD"))
    private void npSecondInject(CallbackInfoReturnable<String> cir) {
        ProbeTarget.hits3++;
    }
}

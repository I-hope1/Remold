package neprobe.mixin;

import neprobe.ProbeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin v1 (baseline): one {@code @Unique} field plus one {@code @Inject} handler.
 *
 * <p>{@code stages/ProbeTargetMixin.v2.java} is the stage 1 hot update: it adds a second
 * {@code @Inject} handler (a new <em>method</em> in the template) — a member that Mixin's internal
 * {@code ClassInfo} does not learn about on its own during a template reload, because
 * {@code MixinInfo.reloadMixin} builds the reloaded state on the <b>previous</b> {@code ClassInfo}.
 * Stage 2 uses the separate {@link ProbeTargetSecondMixin} template.</p>
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
}

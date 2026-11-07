package ciprobe.mixin;

import ciprobe.ClassInfoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin v2 — stage 1 of the Forge ClassInfo probe: a <b>new {@code @Inject} handler method</b>
 * ({@code ciInject2}) added to the template.
 *
 * <p>Without ClassInfo member sync the reload validation cannot find this method in the stale
 * {@code ClassInfo} and the hot update is rejected ({@code Conform error}); with the sync the
 * handler is woven and {@code hits2} starts incrementing.</p>
 */
@Mixin(ClassInfoTarget.class)
public abstract class ClassInfoTargetMixin {
    @Unique
    private int ciUnique = 123;

    @Inject(method = "onReload", at = @At("HEAD"))
    private void ciInject1(CallbackInfo ci) {
        ClassInfoTarget.hits1++;
        ClassInfoTarget.uniqueSeen1 = this.ciUnique;
    }

    @Inject(method = "onReload", at = @At("HEAD"))
    private void ciInject2(CallbackInfo ci) {
        ClassInfoTarget.hits2++;
    }
}

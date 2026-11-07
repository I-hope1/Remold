package ciprobe.mixin;

import ciprobe.ClassInfoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second Mixin on the same target, stage 2 version: a <b>new {@code @Unique} field</b>
 * ({@code ciAdded = 456}) read by the pre-existing handler.
 *
 * <p>Without ClassInfo member sync the field cannot be attached on reload
 * ({@code findField(...) == null} / {@code attachFields} failure), so it never shows up on the
 * re-woven target and {@code uniqueSeen3} never becomes 456.</p>
 */
@Mixin(ClassInfoTarget.class)
public abstract class ClassInfoTargetSecondMixin {
    @Unique
    private int ciAdded = 456;

    @Inject(method = "onReload", at = @At("HEAD"))
    private void ciSecondInject(CallbackInfo ci) {
        ClassInfoTarget.hits3++;
        ClassInfoTarget.uniqueSeen3 = this.ciAdded;
    }
}

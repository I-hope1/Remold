package ciprobe.mixin;

import ciprobe.ClassInfoTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin v1 (baseline): one {@code @Unique} field plus one {@code @Inject} handler.
 *
 * <p>{@code stages/ClassInfoTargetMixin.v2.java} is the stage 1 hot update: it adds a second
 * {@code @Inject} handler (a new <em>method</em> in the template) — a member that Mixin's internal
 * {@code ClassInfo} does not learn about on its own during a template reload. Stage 2 uses the
 * separate {@link ClassInfoTargetSecondMixin} template.</p>
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
}

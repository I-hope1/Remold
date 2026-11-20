package com.example.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * String-form target mixin: {@code @Mixin(targets = "...")} instead of {@code @Mixin(X.class)}.
 *
 * <p>Exists only so the probe can check whether a string target shows up in
 * {@code mixinMapping} / {@code unhandledTargets} / {@code info.getTargetClasses()}
 * the same way a class-literal target does.</p>
 */
@Mixin(targets = "com.example.probe.StringTarget")
public abstract class StringTargetMixin {

	@Inject(method = "value", at = @At("RETURN"), cancellable = true)
	private void remold$stringTargetProbe(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(cir.getReturnValue() + "-woven");
	}
}

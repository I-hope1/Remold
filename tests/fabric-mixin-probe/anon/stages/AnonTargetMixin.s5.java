package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S5 — {@code @Unique private} member access.
 *
 * <p>The hot update adds two {@code @Unique private} members to the target (a field
 * with the constant initializer {@code uq-tag} and a method returning {@code uq-m}).
 * Both are read TWICE: by the handler itself (same-class access) and by the handler's
 * anonymous class through the enclosing instance (cross-class private access inside
 * the nest, which is the shape that produces the {@code Nest scan incomplete}
 * warning while Remold cannot read the Mixin-generated synthetic class's bytes).</p>
 *
 * <p>Observed end result: {@code poke=s5:uq-tag:uq-m|anon:s5:uq-tag:uq-m}. The
 * {@code anon:} half is the cross-class read; if it comes back with an older body
 * string instead, the anonymous class was not redefined and the reading says so
 * instead of silently passing on the handler half. An {@code IllegalAccessError}
 * means the access really fails, not just that a warning was printed.</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Unique
    private String uniqueTag = "uq-tag";

    @Unique
    private String uniqueValue() {
        return "uq-m";
    }

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        Supplier<String> part = new Supplier<String>() {
            @Override
            public String get() {
                return "s5:" + uniqueTag + ":" + uniqueValue();
            }
        };
        cir.setReturnValue("s5:" + uniqueTag + ":" + uniqueValue() + "|anon:" + part.get());
    }
}

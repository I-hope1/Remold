package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S6b — the 1-to-2 same-shape renumbering.
 *
 * <p>A SECOND anonymous class is inserted in FRONT of the existing one: same
 * interface ({@code Supplier<String>}), same member schema, same structure. The
 * existing class is therefore renumbered {@code AnonTargetMixin$1 -> $2}, and its
 * body changes ({@code s6-v1 -> s6-v2}). The new class (now {@code $1}) answers
 * {@code s6-new}, so the old live instance can never be "accidentally right".</p>
 *
 * <p>Observed end result on the long-lived held instance: {@code poke=s6-v2} means
 * the renumbered class was paired with the old one and redefined in place.
 * {@code poke=s6-v1} means the old class survived as an orphan and its live
 * instance keeps running the old code (documented baseline, expected together with
 * {@code [ANON_ALIGN] ... refused to guess ... kept as orphans ... RESTART is
 * required}).</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        Supplier<String> extra = new Supplier<String>() {
            @Override
            public String get() {
                // Structurally different body: the frontline R2b shape. A literal-only
                // sibling is indistinguishable by body structure on purpose, and stays
                // orphaned by design when a live instance holds the old class.
                StringBuilder sb = new StringBuilder();
                sb.append("s6").append('-').append("new");
                return sb.toString();
            }
        };
        if (AnonTarget.HELD6 == null) {
            AnonTarget.HELD6 = new Supplier<String>() {
                @Override
                public String get() {
                    return "s6-v2";
                }
            };
        }
        if (extra == null) {
            cir.setReturnValue("s6-unreachable");
            return;
        }
        cir.setReturnValue(AnonTarget.HELD6.get());
    }
}

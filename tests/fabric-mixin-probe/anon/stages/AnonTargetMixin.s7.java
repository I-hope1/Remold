package com.example.anon;

import java.util.function.Supplier;

import com.example.game.AnonTarget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE S7 — a NEW field whose initializer allocates an anonymous-class instance.
 *
 * <p>{@code s7Tag} does not exist in the target before this stage. After the hot
 * update the target has a new field whose initializer is
 * {@code new AnonTarget$Anonymous$<hash>(this)}, so initializing the LIVE instances
 * requires calling that constructor reflectively. The wire shape that matters is the
 * constructor-visibility question of the Mixin-generated synthetic class:
 * {@code Field init patch refused ... cannot determine protected status of
 * constructor <Target>$Anonymous$<hash>.<init>(<Target>)}.</p>
 *
 * <p>Observed end result on the long-lived instance: {@code poke=s7=s7-v1}. The
 * {@code -fresh} line (a brand new instance, which runs the regenerated constructor)
 * must show the same value in EITHER case — if it does not, the stage itself is
 * broken and the observation is not attributable to the field patch.</p>
 */
@Mixin(AnonTarget.class)
public class AnonTargetMixin {

    @Unique
    private final Supplier<String> s7Tag = new Supplier<String>() {
        @Override
        public String get() {
            return "s7-v1";
        }
    };

    @Inject(method = "poke", at = @At("HEAD"), cancellable = true)
    private void anon$poke(CallbackInfoReturnable<String> cir) {
        Supplier<String> tag = this.s7Tag;
        cir.setReturnValue("s7=" + (tag == null ? "null" : tag.get()));
    }
}

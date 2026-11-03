package com.example.mixin;

import com.example.item.RemoldWandItem;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import remold.annotation.RemoldReinit;

/**
 * 织入本模组自身类的 Mixin，用于演示热更时的「重织入」链路。
 *
 * <p>目标类 {@link RemoldWandItem} 正是会被热重定义的那个类。它的生效字节码由源码字节码
 * 加本 Mixin 织入而来（多出一个 {@code @Unique} 字段和一个 handler 方法）。
 * Remold 重定义前会调用 {@code RemoldMixinBridge}，把新源码字节码重新织入，
 * 因此这两个成员不会在重定义中丢失，{@code redefineClasses} 也不会因为结构变化被拒。</p>
 */
@Mixin(RemoldWandItem.class)
public abstract class RemoldWandItemMixin {

    /**
     * 【动手试试 6：Mixin 中使用 @Unique 与 @RemoldReinit】
     * 试试在 Mixin 类里增加新的 @Unique 字段，并标注 @RemoldReinit：
     * <pre>{@code
     *     @Unique
     *     @RemoldReinit
     *     private String remold$author = "Modder";
     * }</pre>
     * 并在下方的 countUse 注入方法里打印它！按 Ctrl + Shift + F9，Mixin 会自动重新织入并热更生效！
     *
     * <p>👉 <b>下一步</b>：体验动态修改 Mixin 注入切入点或逻辑，按住 Ctrl 点击跳转到【动手试试 7】：
     * {@link #remold$countUse(World, PlayerEntity, Hand, CallbackInfoReturnable)}</p>
     */
    @Unique
    private int remold$useCount = 0;

    /**
     * 【动手试试 7：动态修改 Mixin 注入切入点或逻辑】
     * 试试把 at = @At("HEAD") 改为 at = @At("TAIL")，
     * 观察控制台日志中 mixin 钩子与 [src] use() begin / end 的相对打印顺序立即反转！
     *
     * <p>👉 <b>下一步</b>：体验原版类 (TitleScreen) 的 Mixin 热重载，按住 Ctrl 点击跳转到下一个【动手试试 8】：
     * {@link TitleScreenMixin}</p>
     */
    @Inject(method = "use", at = @At("HEAD"))
    private void remold$countUse(World world, PlayerEntity user, Hand hand,
                                 CallbackInfoReturnable<TypedActionResult<ItemStack>> cir) {
        // 单机里 use() 会被调用两次：客户端本地预测一次 + 集成服务端一次。
        // 物品是注册表里的单例，两侧共用同一个 RemoldWandItem 实例，
        // 所以字段若无条件自增就会变成「一次右键 +2」。这里只在服务端计数。
        if (world.isClient()) {
            return;
        }
        this.remold$useCount++;
        System.out.println("[Remold] mixin hook alive: use() call #" + this.remold$useCount);
    }
}

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

/**
 * 织入本模组自身类的 Mixin，用于演示热更时的「重织入」链路。
 *
 * <p>目标类 {@link RemoldWandItem} 正是会被热重定义的那个类。它的生效字节码由源码字节码
 * 加本 Mixin 织入而来（多出一个 {@code @Unique} 字段和一个 handler 方法）。
 * Remold 重定义前会调用 {@code RemoldMixinBridge}，把新源码字节码重新织入，
 * 因此这两个成员不会在重定义中丢失，{@code redefineClasses} 也不会因为结构变化被拒。</p>
 *
 * <p>关闭重织入（{@code -Dremold.mixin.bridge=false}）后热更本类，可以对比观察：
 * 布局安全门报告字段/方法被移除而拒绝该次热更，模组不会带着残缺字节码继续运行。</p>
 */
@Mixin(RemoldWandItem.class)
public abstract class RemoldWandItemMixin {

    @Unique
    private int remold$useCount = 0;

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
        System.out.println("[Remold] mixin hook alive: use() 第 " + this.remold$useCount + " 次调用");
    }
}

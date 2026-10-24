package com.example.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 演示 Remold 热重载的原位生效物品：
 * 1. 尝试修改 message 中的文字，按 Ctrl + Shift + F9，游戏内右键立即生效！
 * 2. 尝试为本类新增字段（如 public int clickCount = 1;），Remold 的 InitFix 引擎将自动初始化存量实例！
 */
public class RemoldWandItem extends Item {

    // 存量字段：修改默认值或新增字段均可由 Remold 原位热更新
    public String message = "Hello from Remold Fabric Hot Reload!";

    public RemoldWandItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient()) {
            user.sendMessage(Text.literal("§a[Remold] " + message), false);
        }
        return TypedActionResult.success(user.getStackInHand(hand));
    }
}

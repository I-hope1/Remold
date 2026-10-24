package com.example.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import remold.annotation.RemoldReinit;

/**
 * 演示 Remold 热重载的原位生效物品：
 * 1. 尝试修改 message 中的文字，按 Ctrl + Shift + F9，游戏内右键立即生效！
 *    （标有 @RemoldReinit(mode = OVERWRITE) 的字段在热更时会被强制重置为新初值）
 * 2. 尝试为本类新增字段（如 public int clickCount = 1;），Remold 的 InitFix 引擎将自动初始化存量实例！
 * 3. 尝试修改 use() 方法体逻辑（如添加粒子效果或音效），无需重启客户端，秒级生效！
 */
public class RemoldWandItem extends Item {

    // 标注 @RemoldReinit（默认即为 OVERWRITE 模式）：允许热更时覆写存量实例中的已有字段初值
    @RemoldReinit
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

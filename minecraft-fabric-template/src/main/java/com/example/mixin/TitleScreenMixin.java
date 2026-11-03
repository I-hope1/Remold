package com.example.mixin;

import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    // =========================================================================================
    // 【动手试试 8：游戏主界面 (TitleScreen) 原版类的 Mixin 热重载】
    // 游戏运行中直接修改下方的输出信息（例如增加当前时间或自定义欢迎标语），
    // 按 Ctrl + Shift + F9，从世界退出返回主界面，即可看到最新的日志输出立即生效！
    // =========================================================================================
    @Inject(at = @At("HEAD"), method = "init()V")
    private void init(CallbackInfo info) {
        System.out.println("[Remold] TitleScreen initialized! Mixin active with Remold bytecode bridge.");
    }

    // =========================================================================================
    // 🎉 恭喜！你已体验完全部 8 个热重载核心场景！
    // 随时按住 Ctrl 点击跳转返回起点：{@link com.example.item.RemoldWandItem}
    // 官方主仓库与进阶机制说明：https://github.com/I-hope1/Remold
    // =========================================================================================
}

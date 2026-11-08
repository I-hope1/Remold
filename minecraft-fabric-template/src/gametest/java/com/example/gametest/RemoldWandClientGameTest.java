package com.example.gametest;

import java.lang.reflect.Field;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【客户端 gametest】在真客户端里验证 remold_wand（MC 1.21.4 + fabric-client-gametest-api-v1）：
 *
 * <ol>
 *   <li>物品已注册（1.21.4 依赖 {@code assets/example_mod/items/remold_wand.json} 物品定义）；</li>
 *   <li>热栏里模型能渲染 —— 截图 + 模板比对（模型缺失/贴图缺失会是紫黑方块，必然对不上）；</li>
 *   <li>右键真的触发 {@code use()} —— 聊天栏出现 {@code [Remold]} 消息。</li>
 * </ol>
 *
 * <p>运行：{@code ./gradlew runClientGameTest}。模板图放在
 * {@code src/gametest/resources/templates/}。</p>
 */
public class RemoldWandClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("example_mod_gametest");
    private static final Identifier WAND_ID = Identifier.of("example_mod", "remold_wand");

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);

        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(true)
                .create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            LOGGER.info("[GAMETEST] 测试世界已就绪");

            final Item wand = Registries.ITEM.get(WAND_ID);
            check(wand != null, "example_mod:remold_wand 没有注册");

            // 创造模式 + 把法杖放进热栏第 0 格
            singleplayer.getServer().runCommand("gamemode creative @a");
            singleplayer.getServer().runCommand("item replace entity @a hotbar.0 with example_mod:remold_wand");
            context.waitTicks(10);
            context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
            context.waitTicks(5);

            // ① 视觉断言：热栏里的法杖与模板一致
            context.takeScreenshot("wand-hotbar");
            context.assertScreenshotContains("wand_hotbar");
            LOGGER.info("[GAMETEST] ✅ 热栏模型截图断言通过");

            // ② 行为断言：真实右键 -> 服务端执行 use() -> 聊天栏出现 [Remold]
            context.runOnClient(client -> client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
            context.waitTicks(10);
            final String chat = context.computeOnClient(RemoldWandClientGameTest::findRemoldChatLine);
            check(chat != null, "右键后聊天栏没有出现 [Remold] 消息");
            LOGGER.info("[GAMETEST] ✅ 聊天栏命中: {}", chat);
            context.takeScreenshot("wand-used");
        }
    }

    /** 聊天栏里第一条含 [Remold] 的消息（ChatHud.messages 是私有的，测试里用反射读）。 */
    private static String findRemoldChatLine(MinecraftClient client) {
        try {
            ChatHud hud = client.inGameHud.getChatHud();
            Field field = ChatHud.class.getDeclaredField("messages");
            field.setAccessible(true);
            List<?> messages = (List<?>) field.get(hud);
            for (Object entry : messages) {
                String text = ((ChatHudLine) entry).content().getString();
                if (text.contains("[Remold]")) {
                    return text;
                }
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[GAMETEST] 读取 ChatHud.messages 失败", e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[GAMETEST] " + message);
        }
    }
}

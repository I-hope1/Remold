package com.example.item;

import com.example.mixin.RemoldWandItemMixin;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.ClickEvent.Action;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import remold.annotation.OnReload;
import remold.annotation.RemoldReinit;

/**
 * 演示 Remold 热重载的原位生效物品：
 *
 * <p>直接按下方各处【动手试试】修改代码并按快捷键 {@code Ctrl + Shift + F9}（IntelliJ），
 * 保持游戏在后台运行，观察秒级原位热更新！</p>
 */
public class RemoldWandItem extends Item {

    /**
     * 【动手试试 1：修改已有字段初值（{@code @RemoldReinit}）】
     * <p>试试把下面的 {@link #message} 改成任意你喜欢的文本（例如 "🔥 自研火球投掷物已发射！"），
     * 或者把 {@link #fireballPower} 从 21 改成 100。</p>
     * <p>按 {@code Ctrl + Shift + F9}，进游戏右键该物品：手持法杖的存量实例立即同步最新初值！</p>
     */
    @RemoldReinit
    public String message = "Hello from Remold Fabric Hot Reload!";

    /**
     * 【动手试试 2：动态变更字段类型（Field Type Changed）】
     * <p>试试把下方标有 {@code @RemoldReinit} 的 {@link #fireballPower} 改成 {@code String} 类型：</p>
     * <pre>{@code
     *     @RemoldReinit
     *     public String fireballPower = "MAX_POWER";
     * }</pre>
     * <p>{@link #use} 里对 {@link #fireballPower} 已是字符串拼接，
     * 无需改动即可直接打印新类型。</p>
     * <p>按 {@code Ctrl + Shift + F9}：Remold 会在底层安全重定义并重新初始化存量实例，零重启完成类型平滑过渡！</p>
     *
     * <p>【动手试试 3：新增全新字段（InitFix 自动装配）】</p>
     * <p>试着在下方新增一个你自己的字段（即使不标 {@code @RemoldReinit} 也是安全的）：</p>
     * <pre>{@code
     *     public int clickCount = 10;
     * }</pre>
     * <p>并在 {@link #use} 中写 {@code clickCount++}。
     * Remold 的 InitFix 引擎会在重定义后自动给存量实例注入初值，绝不读到 {@code 0}！</p>
     *
     * <p>👉 <b>下一步</b>：体验修改方法体业务逻辑，按住 {@code Ctrl} 点击跳转到【动手试试 4】：
     * {@link #use}</p>
     */
    @RemoldReinit
    public int fireballPower = 21;

    public RemoldWandItem(Settings settings) {
        super(settings);
    }

    /**
     * 【动手试试 4：修改方法体业务逻辑】
     * <p>试试直接修改下面的方法体：</p>
     * <ol>
     *   <li>添加音效：<pre>{@code
     *     user.playSound(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
     *   }</pre></li>
     *   <li>添加粒子：<pre>{@code
     *     world.addParticle(ParticleTypes.FLAME, user.getX(), user.getY() + 1, user.getZ(), 0, 0.1, 0);
     *   }</pre></li>
     *   <li>改变玩家状态：<pre>{@code
     *     user.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 100, 1));
     *   }</pre></li>
     * </ol>
     * <p>按 {@code Ctrl + Shift + F9}，进游戏右键直接生效，无需退出存档！</p>
     *
     * <p>👉 <b>下一步</b>：体验热重载生命周期回调，按住 {@code Ctrl} 点击跳转到【动手试试 5】：
     * {@link #onReload()}</p>
     */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient()) {
            // 方法体首尾各一行标记，用来观察 Mixin 注入点的实际位置：
            // @At("HEAD") 时钩子打印在 begin 之前；@At("TAIL") 时打印在 end 之后。
            System.out.println("[src] use() begin");
            user.sendMessage(Text.literal("§a[Remold] " + message + " (威力: " + fireballPower + ")"), false);
            user.sendMessage(Text.literal("§b👉 [点击跳转 Remold 官方仓库]")
                .styled(style -> style.withClickEvent(new ClickEvent(
                    Action.OPEN_URL, "https://github.com/I-hope1/Remold"))), false);
            System.out.println("[src] use() end");
        }
        return TypedActionResult.success(user.getStackInHand(hand));
    }

    /**
     * 【动手试试 5：监听热更生命周期（{@code @OnReload}）】
     * <p>每次热重载成功后，Remold 会自动对当前内存中本类的每个存活实例回调一次标记了 {@code @OnReload} 的方法。</p>
     *
     * <p>👉 <b>下一步</b>：体验 Mixin 钩子与 {@code @Unique} 字段热更，按住 {@code Ctrl} 点击跳转到下一个【动手试试 6 & 7】：
     * {@link RemoldWandItemMixin}</p>
     *
     * <p>更多进阶文档与原理解析请见 Remold 官方主仓库：<a href="https://github.com/I-hope1/Remold">Remold</a></p>
     */
    @OnReload
    public void onReload() {
        System.out.println(">>> [Remold] RemoldWandItem 热重载成功回调！当前 message=" + message + ", fireballPower=" + fireballPower);
    }
}

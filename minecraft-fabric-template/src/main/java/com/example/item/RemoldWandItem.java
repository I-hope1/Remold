package com.example.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import remold.annotation.OnReload;
import remold.annotation.RemoldReinit;

/**
 * 演示 Remold 热重载的原位生效物品：
 *
 * <p>直接按下方各处【动手试试】修改代码并按快捷键（IntelliJ: Ctrl + Shift + F9），
 * 保持游戏在后台运行，观察秒级原位热更新！</p>
 */
public class RemoldWandItem extends Item {

    // =========================================================================================
    // 【动手试试 1：修改已有字段初值（@RemoldReinit）】
    // 试试把下面的 message 改成任意你喜欢的文本（例如 "🔥 自研火球投掷物已发射！"），
    // 或者把 fireballPower 从 21 改成 100。
    // 按 Ctrl + Shift + F9，进游戏右键该物品：手持法杖的存量实例立即同步最新初值！
    // =========================================================================================
    @RemoldReinit
    public String message = "Hello from Remold Fabric Hot Reload!";

    @RemoldReinit
    public int fireballPower = 21;

    // =========================================================================================
    // 【动手试试 2：动态变更字段类型（Field Type Changed）】
    // 试试把上面标有 @RemoldReinit 的 fireballPower 改成 String 类型：
    //     @RemoldReinit
    //     public String fireballPower = "MAX_POWER";
    // 并在下方 use() 中改为 user.sendMessage(Text.literal("威力级别: " + fireballPower));
    // 按 Ctrl + Shift + F9：Remold 会在底层安全重定义并重新初始化存量实例，零重启完成类型平滑过渡！
    // =========================================================================================

    // =========================================================================================
    // 【动手试试 3：新增全新字段（InitFix 自动装配）】
    // 试试取消下面这一行的注释，或者新增你自己的字段（即使不标 @RemoldReinit 也是安全的）：
    //     public int clickCount = 1;
    // 并在 use() 中写 clickCount++。Remold 的 InitFix 引擎会在重定义后自动给存量实例注入初值，绝不读到 0！
    // =========================================================================================

    public RemoldWandItem(Settings settings) {
        super(settings);
    }

    // =========================================================================================
    // 【动手试试 4：修改方法体业务逻辑】
    // 试试直接修改下面的方法体：
    // 1. 添加音效：user.playSound(net.minecraft.sound.SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
    // 2. 添加粒子：world.addParticle(net.minecraft.particle.ParticleTypes.FLAME, user.getX(), user.getY() + 1, user.getZ(), 0, 0.1, 0);
    // 3. 改变玩家状态：user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SPEED, 100, 1));
    // 按 Ctrl + Shift + F9，进游戏右键直接生效，无需退出存档！
    // =========================================================================================
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient()) {
            // 方法体首尾各一行标记，用来观察 Mixin 注入点的实际位置：
            // @At("HEAD") 时钩子打印在 begin 之前；@At("TAIL") 时打印在 end 之后。
            System.out.println("[src] use() begin");
            user.sendMessage(Text.literal("§a[Remold] " + message + " (威力: " + fireballPower + ")"), false);
            user.sendMessage(Text.literal("§b👉 [点击跳转 Remold 官方仓库]")
                .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent(
                    net.minecraft.text.ClickEvent.Action.OPEN_URL, "https://github.com/I-hope1/Remold"))), false);
            System.out.println("[src] use() end");
        }
        return TypedActionResult.success(user.getStackInHand(hand));
    }

    // =========================================================================================
    // 【动手试试 5：监听热更生命周期（@OnReload）】
    // 每次热重载成功后，Remold 会自动对当前内存中本类的每个存活实例回调一次标记了 @OnReload 的方法：
    // =========================================================================================
    @OnReload
    public void onReload() {
        System.out.println(">>> [Remold] RemoldWandItem 热重载成功回调！当前 message=" + message + ", fireballPower=" + fireballPower);
    }

    // =========================================================================================
    // 👉 下一步：体验 Mixin 钩子与 @Unique 字段热更
    // 按住 Ctrl 点击跳转到下一个【动手试试 6 & 7】：
    // {@link com.example.mixin.RemoldWandItemMixin}
    //
    // 更多进阶文档与原理解析请见 Remold 官方主仓库：https://github.com/I-hope1/Remold
    // =========================================================================================
}

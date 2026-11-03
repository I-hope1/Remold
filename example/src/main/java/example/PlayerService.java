package example;

import remold.annotation.OnReload;
import remold.annotation.RemoldReinit;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class PlayerService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String name;
    private int score;
    private int level = 1;

    /**
     * 【动手试试 1：修改已有字段初值（{@code @RemoldReinit}）】
     * <p>标注 {@code @RemoldReinit(mode = OVERWRITE)}：允许热重载时强制将新初值覆写到存量运行实例上。</p>
     * <p>保持进程运行，试着修改下方 {@link #statusTitle} 的初值，例如改成 "Legendary Master"：
     * 在 IDEA 中按 {@code Ctrl + Shift + F9}（或在另一个终端运行 {@code ./gradlew :example:compileJava}），
     * 观察终端输出：{@link #statusTitle} 被即时重置，且存活实例的其他状态（如累加的 {@link #score}）完好保留！</p>
     *
     * <p>【动手试试 2：动态变更字段类型（Field Type Changed）】</p>
     * <p>Remold 支持运行期动态改变字段类型：
     * 试着将 {@link #statusTitle} 的类型从 {@code String} 改为 {@code int}：</p>
     * <pre>{@code
     *   @RemoldReinit
     *   private int statusTitle = 999;
     * }</pre>
     * <p>并将 {@link #tick()} 中的 {@code %s} 适配为 {@code %d}。重新编译，Remold 经 {@code @RemoldReinit} 授权直接放行！</p>
     *
     * <p>【动手试试 3：新增全新字段（InitFix 自动装配初值）】</p>
     * <p>试着新增一个普通字段（不需要标 {@code @RemoldReinit}）：</p>
     * <pre>{@code
     *   private String guild = "Remold Adventurers";
     *   private int rank = 10;
     * }</pre>
     * <p>重新编译，Remold 的 InitFix 引擎会自动安全回溯构造器并为内存中的存量实例装配初值，绝不为 {@code null}/{@code 0}！</p>
     *
     * <p>👉 <b>下一步</b>：体验修改方法体与业务逻辑，按住 {@code Ctrl} 点击跳转到【动手试试 4】：
     * {@link #tick()}</p>
     */
    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
    private String statusTitle = "Novice Adventurer";

    public PlayerService(String name) {
        this.name = name;
        this.score = 100;
    }

    /**
     * 【动手试试 4：修改业务逻辑与方法体】
     * <p>试着修改下方 {@link #tick()} 中的得分增长逻辑（例如改为 {@code this.score += 100}）或输出格式，
     * 重新编译，下一次循环即刻应用新逻辑，无需重启 Java 进程！</p>
     *
     * <p>👉 <b>下一步</b>：体验热重载生命周期回调，按住 {@code Ctrl} 点击跳转到【动手试试 5】：
     * {@link #onReload()}</p>
     */
    public void tick() {
        this.score += 10;
        System.out.printf("[%s] [PlayerService] Name: %s | Title: %s | Level: %d | Score: %d%n",
                LocalTime.now().format(TIME_FORMAT), name, statusTitle, level, score);
    }

    /**
     * 【动手试试 5：监听热重载生命周期（{@code @OnReload}）】
     * <p>存量实例被热更新后，Remold 会自动调用标有 {@code @OnReload} 的方法。
     * 试着修改 {@link #onReload()} 方法体，例如打印更多自定义调试信息或重置临时计数器。</p>
     *
     * <p>本类被热更重定义后，Remold 会对<b>每个存活实例</b>各调用一次本方法（这里是实例方法，
     * 不是"应用收到热更通知"）：应用里创建了几个 PlayerService，下面这段就打印几次。</p>
     *
     * <p>👉 <b>下一步</b>：体验匿名内部类与 Lambda 表达式热重载，按住 {@code Ctrl} 点击跳转到下一个【动手试试 6】：
     * {@link Main#main(String[])}</p>
     */
    @OnReload
    private void onReload() {
        System.out.println("----------------------------------------------------------------");
        System.out.printf(">>> [HOTSWAP] @OnReload triggered for Player: %s at %s! <<<%n",
                name, LocalTime.now().format(TIME_FORMAT));
        System.out.println(">>> Notice that live instance state (like score=" + score + ") is safely preserved! <<<");
        System.out.println("----------------------------------------------------------------");
    }
}

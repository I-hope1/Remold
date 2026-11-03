package example;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.TextureRegion;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.ui.dialogs.BaseDialog;
import remold.annotation.OnReload;
import remold.annotation.RemoldReinit;

/**
 * Mindustry 现代 Java 模组模板（集成 Remold 零侵入热重载）。
 *
 * <p><b>【代码热重载体验指引】</b>：
 * <ol>
 *   <li>终端运行 {@code ./gradlew runRemold} 启动游戏（首次会自动打包并复制到系统 mods 目录）；</li>
 *   <li>保持游戏在后台运行，修改本类代码（例如把下方的 {@link #message} 改为别的内容）；</li>
 *   <li>在 IntelliJ IDEA 中对本文件按快捷键 {@code Ctrl + Shift + F9}（重新编译）；</li>
 *   <li>观察游戏内输出：Remold 会在 0.5 秒内自动原位热替换，无需重启游戏或重新进存档！</li>
 * </ol>
 * </p>
 *
 * <p><b>【PNG 资源热更新体验指引】</b>：
 * 进游戏后右侧边缘常驻的角标来自 {@code assets/sprites/remold-badge.png}。Mindustry 会把该文件
 * 注册成名为 {@code <模组内部名>-remold-badge} 的图集区域；游戏运行期间直接改写这张 PNG 并保存，
 * {@code plugin-arc} 的贴图重载器就会在主线程把新像素写进显存，画面即时变化，无需重新部署。
 * 分支与限制见 {@link #badgeRegion()} 的说明。
 * </p>
 */
public class ExampleJavaMod extends Mod {

    /** 角标贴图的文件名（不含扩展名），对应 {@code assets/sprites/remold-badge.png}。 */
    private static final String BADGE_SPRITE = "remold-badge";

    /** HUD 角标容器；置为静态，避免同一进程内重复挂载。 */
    private static Table badge;

    /**
     * 【动手试试 1：修改已有字段初值（@RemoldReinit）】
     * 标注 @RemoldReinit（默认即为 OVERWRITE 模式）：允许热更时强制覆写存量/静态字段初值。
     * 试着修改下方字符串（例如改成 "🎉 恭喜！Mindustry 模组秒级原位热重载成功！"），
     * 在 IDEA 中按 Ctrl+Shift+F9（重新编译），观察控制台输出：
     *   [REINIT] @RemoldReinit applied to example/ExampleJavaMod: message (OVERWRITE, static)
     * 重置时机是"这个初值表达式变了"，不是"类被热更了" —— 只改别的方法体不会把值打回去；
     * 生效时控制台会多打一行 [REINIT] 用来和"本次新增字段被初始化"区分开。
     *
     * <p>【动手试试 2：动态变更字段类型（Field Type Changed）】
     * Remold 支持在运行期动态切换已有字段的数据类型（只要批次外无类依赖旧类型）：
     * 试着把 String 改为 int：
     * <pre>{@code
     *   @RemoldReinit
     *   public static int message = 2026;
     * }</pre>
     * 并相应把用到 message 的地方改为字符串拼接。按 Ctrl+Shift+F9 重新编译，
     * Remold 经 @RemoldReinit 授权放行，存量实例与静态环境无缝迁移至新类型与新初值！
     * </p>
     *
     * <p>【动手试试 3：新增全新字段（InitFix 自动装配初值）】
     * 试着新增一个普通静态或实例字段（不需要带 @RemoldReinit）：
     * <pre>{@code
     *   public static String subTitle = "由 Remold InitFix 自动装配初始值";
     *   public int sessionScore = 100 * 2;
     * }</pre>
     * 按 Ctrl+Shift+F9 重新编译，InitFix 会自动为存量环境执行安全初始化，绝不为 null/0！
     * </p>
     *
     * <p>👉 <b>下一步</b>：体验热重载生命周期回调，按住 Ctrl 点击跳转到【动手试试 4】：
     * {@link #onReload()}</p>
     */
    @RemoldReinit
    public static String message = "Hello Remold Hot Reloading!";

    public static int reloadCount = 0;

    public ExampleJavaMod() {
        Log.info("[ExampleJavaMod] Constructor loaded. Message: @", message);

        // 监听客户端加载完毕事件
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, () -> {
            showBadge();
            showDialog();
        }));
    }

    @Override
    public void loadContent() {
        Log.info("[ExampleJavaMod] Loading content... (Edit this and press Ctrl+Shift+F9 to see live updates)");
    }

    /**
     * 【动手试试 4：监听热重载生命周期（@OnReload）】
     * 本类被热更重定义后，Remold 会对<b>本类的每个存活实例</b>各调用一次本方法。
     * 试着在下方加入游戏内浮动提示：
     * <pre>{@code
     *   if (Vars.ui != null) Vars.ui.showInfoToast("[green]模组热更新成功！重载次数: " + reloadCount, 3f);
     * }</pre>
     * 按 Ctrl+Shift+F9 重新编译，每次热重载都会弹出游戏内浮窗，实时反馈！
     *
     * <p>注意这是<b>对实例的回调</b>，不是"模组收到热更通知"：它之所以看起来像模组级事件，只是因为
     * Mindustry 在进程里恰好只持有一个 {@code Mod} 实例。把同样的注解写到有多个实例的类上会被调用
     * 多次；写到纯静态工具类上（零实例）则一次都不会调用 —— 那种场景请改用 {@code static} 方法，
     * 静态方法每轮热更调用一次。</p>
     *
     * <p>👉 <b>下一步</b>：体验 PNG 贴图原位热更新（零重启直刷显存），按住 Ctrl 点击跳转到【动手试试 5】：
     * {@link #showBadge()}</p>
     */
    @OnReload
    public void onReload() {
        reloadCount++;
        Log.info("[ExampleJavaMod] @OnReload triggered! Reload count: @, Current message: @", reloadCount, message);
    }

    /**
     * 解析角标贴图在 {@code Core.atlas} 中的区域名。
     *
     * <p>Mindustry 加载模组时把 {@code sprites/} 下的文件按 {@code <模组内部名>-<文件名>} 注册进图集，
     * 模组内部名取自 {@code mod.hjson} 的 {@code name} 字段（小写化、空格转连字符）。</p>
     */
    private static String badgeRegionName() {
        LoadedMod mod = Vars.mods == null ? null : Vars.mods.getMod(ExampleJavaMod.class);
        String prefix = mod != null ? mod.name : "example-remold-mod";
        return prefix + "-" + BADGE_SPRITE;
    }

    /**
     * 取回角标贴图的图集区域。
     *
     * <p>这里返回的 {@link TextureRegion} 对象在图集生命周期内保持稳定：热更新只改写该区域在显存里的
     * 像素，不会替换对象本身，所以 UI 控件持有它即可自动跟随刷新。两种刷新路径：</p>
     * <ul>
     *   <li><b>尺寸不变</b>：原位直刷（{@code Gl.texSubImage2D}），零材质重建；</li>
     *   <li><b>尺寸改变</b>：额外的图集缓存清理与 {@code Tex}/{@code Styles}/内容图标重载，但区域自身的
     *       UV 与尺寸不会更新，画面可能被拉伸或溢出，换尺寸应重新部署模组。</li>
     * </ul>
     *
     * <p>新增一张图集里尚不存在的贴图时，{@code plugin-arc} 会为它注册一张独立纹理，区域名按「重启后会用的名字」
     * 推导（{@code sprites/} 加模组前缀、{@code sprites-override/} 不加）；推不出归属模组则拒绝注册并提示重启。</p>
     */
    private static TextureRegion badgeRegion() {
        if (Core.atlas == null) return null;

        String name = badgeRegionName();
        if (!Core.atlas.has(name)) {
            Log.warn("[ExampleJavaMod] Atlas region '@' not found; package assets/sprites/@.png and restart once.", name, BADGE_SPRITE);
            return null;
        }
        return Core.atlas.find(name);
    }

    /**
     * 【动手试试 5：PNG 贴图原位热更新（零重启游戏）】
     * 模板在 assets/sprites/remold-badge.png 准备了常驻角标贴图（并在屏幕右侧显示）。
     * 游戏运行期间，直接用画图/绘图工具编辑该 PNG（或复制 demo/remold-badge-amber.png 覆盖它）：
     * <pre>
     *   Windows: copy demo\remold-badge-amber.png assets\sprites\remold-badge.png
     *   macOS/Linux: cp demo/remold-badge-amber.png assets/sprites/remold-badge.png
     * </pre>
     * 切回游戏，屏幕右侧常驻的角标贴图瞬间直刷显存变色，无需重新启动游戏或重新部署模组！
     *
     * <p>在 HUD 右侧边缘挂一个常驻角标，作为贴图热更新的观察窗口。</p>
     *
     * <p>👉 <b>下一步</b>：体验动态修改 UI 对话框与交互，按住 Ctrl 点击跳转到【动手试试 6】：
     * {@link #showDialog()}</p>
     */
    private static void showBadge() {
        if (badge != null || Vars.ui == null || Vars.ui.hudGroup == null) return;

        TextureRegion region = badgeRegion();
        if (region == null) return;

        Table table = new Table();
        table.setFillParent(true);
        // 右侧竖直居中：避开左上核心资源、右上波次信息与底部快捷栏
        table.right();
        // 纯展示，不拦截输入
        table.touchable = Touchable.disabled;

        Image image = new Image(region);
        image.setScaling(Scaling.fit);
        table.add(image).size(80f).padRight(12f);

        Vars.ui.hudGroup.addChild(table);
        badge = table;
        Log.info("[ExampleJavaMod] Badge bound to atlas region '@'; edit assets/sprites/@.png to hot-reload it.", badgeRegionName(), BADGE_SPRITE);
    }

    /**
     * 【动手试试 6：动态修改 UI 对话框与交互逻辑】
     * 试着给下面的对话框新增一个按钮，或者修改提示文案与窗口标题：
     * <pre>{@code
     *   dialog.cont.button("动态新增的按钮", () -> Vars.ui.showInfo("来自热更后的点击响应！")).size(160f, 50f).row();
     * }</pre>
     * 按 Ctrl+Shift+F9 重新编译，重新调用 showDialog()（或者触发对应事件）即可看到最新 UI。
     *
     * <p>👉 <b>恭喜通关！</b>按住 Ctrl 点击循环跳转回起点【动手试试 1】：
     * {@link #message}</p>
     *
     * <p>想了解更多关于 Remold 的热重载机制、Arc 全套资源热更与安全门设计：
     * 请访问 Remold 官方主仓库：https://github.com/I-hope1/Remold</p>
     */
    private static void showDialog() {
        BaseDialog dialog = new BaseDialog("Remold Mod Template");

        TextureRegion region = badgeRegion();
        if (region != null) {
            dialog.cont.image(region).size(128f).pad(8f).row();
        }

        dialog.cont.add(message).pad(10f).row();
        dialog.cont.add("[lightgray]Edit assets/sprites/" + BADGE_SPRITE + ".png to hot-reload this icon").pad(4f).row();
        dialog.cont.button("Remold GitHub", () -> arc.Core.app.openURI("https://github.com/I-hope1/Remold")).size(140f, 50f);
        dialog.cont.button("I see", dialog::hide).size(120f, 50f);
        dialog.show();
    }
}

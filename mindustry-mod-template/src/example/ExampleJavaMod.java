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

    // 标注 @RemoldReinit（默认即为 OVERWRITE 模式）：允许热更时强制覆写存量/静态字段初值
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
     * 当本模组被热更后，Remold 会自动调度执行此生命周期回调。
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

    /** 在 HUD 右侧边缘挂一个常驻角标，作为贴图热更新的观察窗口。 */
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

    private static void showDialog() {
        BaseDialog dialog = new BaseDialog("Remold Mod Template");

        TextureRegion region = badgeRegion();
        if (region != null) {
            dialog.cont.image(region).size(128f).pad(8f).row();
        }

        dialog.cont.add(message).pad(10f).row();
        dialog.cont.add("[lightgray]Edit assets/sprites/" + BADGE_SPRITE + ".png to hot-reload this icon").pad(4f).row();
        dialog.cont.button("I see", dialog::hide).size(120f, 50f);
        dialog.show();
    }
}

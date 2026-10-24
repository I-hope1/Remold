package example;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.ui.dialogs.*;
import remold.annotation.OnReload;
import remold.annotation.RemoldReinit;

/**
 * Mindustry 现代 Java 模组模板（集成 Remold 零侵入热重载）。
 *
 * <p><b>【热重载体验指引】</b>：
 * <ol>
 *   <li>终端运行 {@code ./gradlew runRemold} 启动游戏（首次会自动打包并复制到系统 mods 目录）；</li>
 *   <li>保持游戏在后台运行，修改本类代码（例如把下方的 {@link #message} 改为别的内容）；</li>
 *   <li>在 IntelliJ IDEA 中对本文件按快捷键 {@code Ctrl + Shift + F9}（重新编译）；</li>
 *   <li>观察游戏内输出：Remold 会在 0.5 秒内自动原位热替换，无需重启游戏或重新进存档！</li>
 * </ol>
 * </p>
 */
public class ExampleJavaMod extends Mod {

    // 标注 @RemoldReinit（默认即为 OVERWRITE 模式）：允许热更时强制覆写存量/静态字段初值
    @RemoldReinit
    public static String message = "Hello Remold Hot Reloading!";

    public static int reloadCount = 0;

    public ExampleJavaMod() {
        Log.info("[ExampleJavaMod] Constructor loaded. Message: @", message);

        // 监听客户端加载完毕事件
        Events.on(ClientLoadEvent.class, e -> {
            Time.runTask(10f, () -> {
                BaseDialog dialog = new BaseDialog("Remold Mod Template");
                dialog.cont.add(message).pad(10f).row();
                dialog.cont.button("I see", dialog::hide).size(120f, 50f);
                dialog.show();
            });
        });
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
}

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

    // 新增字段热注入演示：默认仅在存量实例中字段为零值时注入初值；指定 OVERWRITE 则强制重置
    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
    private String statusTitle = "Novice Adventurer";

    public PlayerService(String name) {
        this.name = name;
        this.score = 100;
    }

    public void tick() {
        this.score += 10;
        System.out.printf("[%s] [PlayerService] Name: %s | Title: %s | Level: %d | Score: %d%n",
                LocalTime.now().format(TIME_FORMAT), name, statusTitle, level, score);
    }

    /**
     * 本类被热更重定义后，Remold 会对<b>每个存活实例</b>各调用一次本方法（这里是实例方法，
     * 不是"应用收到热更通知"）：应用里创建了几个 PlayerService，下面这段就打印几次。
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

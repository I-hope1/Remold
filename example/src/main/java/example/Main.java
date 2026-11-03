package example;

public class Main {

    /**
     * 应用主运行入口。
     *
     * <p>【动手试试 6：匿名内部类与 Lambda 表达式热重载】
     * 保持进程运行，试着在下方的 {@code Runnable} 匿名内部类中添加业务打印，或者将其重构成带有局部变量捕获的 Lambda：
     * <pre>{@code
     *   Runnable task = () -> System.out.println("  [Lambda] Hot reloaded at tick: " + tickCount);
     * }</pre>
     * Remold 的 AnonClassAligner（4 阶拓扑对齐）与 LambdaAligner 会自动进行编号对齐与 CallSite 映射，
     * 彻底杜绝匿名类/Lambda 增删重排导致的 NoSuchMethodError 崩溃！
     * </p>
     *
     * <p>👉 <b>恭喜通关！</b>按住 Ctrl 点击循环跳转回起点【动手试试 1~5】：
     * {@link PlayerService}</p>
     *
     * <p>更多进阶文档与原理解析请见 Remold 官方主仓库：https://github.com/I-hope1/Remold</p>
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("================================================================");
        System.out.println("  Remold Example Application Started!");
        System.out.println("  1. Keep this process running.");
        System.out.println("  2. In another terminal, run:");
        System.out.println("     ./gradlew :example:compileJava");
        System.out.println("     (or recompile with Ctrl+Shift+F9 in IntelliJ IDEA)");
        System.out.println("  3. Observe immediate hot reload & @OnReload callback in output!");
        System.out.println("================================================================");

        PlayerService player = new PlayerService("Hero");

        int tickCount = 0;
        while (true) {
            tickCount++;
            player.tick();

            // 【动手试试 6：匿名内部类与 Lambda 表达式热重载】详见本方法 main() 的 Javadoc 文档注释
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    // Anonymous class execution
                }
            };
            task.run();

            Thread.sleep(1000);
        }
    }
}

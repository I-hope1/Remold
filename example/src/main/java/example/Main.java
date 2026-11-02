package example;

public class Main {
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

            // =================================================================================
            // 【动手试试 6：匿名内部类与 Lambda 表达式热重载】
            // 试着在下面的 Runnable 匿名内部类中添加业务打印，或者将其重构成带有局部变量捕获的 Lambda：
            //   Runnable task = () -> System.out.println("  [Lambda] Hot reloaded at tick: " + tickCount);
            // Remold 的 AnonClassAligner（4 阶拓扑对齐）与 LambdaAligner 会自动进行编号对齐与 CallSite 映射，
            // 彻底杜绝匿名类/Lambda 增删重排导致的 NoSuchMethodError 崩溃！
            // =================================================================================
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

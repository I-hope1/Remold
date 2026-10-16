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

            // 演示匿名类调用：即使修改匿名类实现，Remold 拓扑对齐也能确保不会错位
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

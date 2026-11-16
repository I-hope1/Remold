import remold.HotswapBatchListener;
import remold.RemoldAgent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 构建完成 marker 的端到端探针（真实 agent + 真实文件监听 + 真实 DCEVM redefine）。
 *
 * <p>运行：{@code ./gradlew :tests:buildTriggerProbe -Pp05.jbr=<DCEVM JDK>}（不挂 {@code check}）。</p>
 *
 * <p>场景与断言见 {@code docs/topology/14-build-trigger-mode.md} §7：快路径 vs 去触发对照、
 * 无关 marker 不触发、取消兜底、构建中途、陈旧 marker。耗时按 marker 写入 → 批次回调统计
 * min/median/max（同轮同口径）并打印原始样本。</p>
 */
public class BuildTriggerProbe {

	static int passed = 0;
	static int failed = 0;

	static Path rootDir;
	static Path classesDir;
	static Path srcDir;
	static Path marker;
	static String fixture = "demo.Subject";
	static URLClassLoader loader;
	static Method valueMethod;

	static volatile CountDownLatch batchLatch = new CountDownLatch(0);
	static final AtomicLong lastBatchNanos = new AtomicLong();
	static final AtomicInteger batchCount = new AtomicInteger();
	static final AtomicInteger lastBatchSize = new AtomicInteger();
	static final AtomicInteger markerSeq = new AtomicInteger();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== BuildTriggerProbe: build-completion marker fast path ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		rootDir = Paths.get(System.getProperty("buildTriggerProbe.dir",
			Paths.get("build", "build-trigger-probe").toString())).toAbsolutePath();
		classesDir = rootDir.resolve("classes");
		srcDir = rootDir.resolve("src");
		String markerProp = System.getProperty("remold.agent.build_trigger_file");
		if (markerProp == null) {
			System.err.println("[FAIL] remold.agent.build_trigger_file is not set");
			System.exit(1);
		}
		marker = Paths.get(markerProp).toAbsolutePath();
		System.out.println("   root=" + rootDir);
		System.out.println("   marker=" + marker);
		deleteRecursively(rootDir);
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				lastBatchNanos.set(System.nanoTime());
				lastBatchSize.set(redefinedClasses == null ? 0 : redefinedClasses.size());
				batchCount.incrementAndGet();
				CountDownLatch latch = batchLatch;
				if (latch != null) latch.countDown();
			}
		});

		compile("v1");
		loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
			BuildTriggerProbe.class.getClassLoader());
		Class<?> subject = Class.forName(fixture, true, loader);
		valueMethod = subject.getMethod("value");
		checkEq("v1", call(), "precondition: v1 is loaded and running");

		RemoldAgent.setWatchPaths("probe", List.of(classesDir), List.of());
		Thread.sleep(500L);
		checkEq("v1", call(), "precondition: agent scan must not redefine an unchanged class");

		scenarioFastPath();
		scenarioFallbackControl();
		scenarioUnrelatedMarker();
		scenarioCancelsPendingFallback();
		scenarioBuildInProgress();
		scenarioStaleMarker();

		System.out.println("\n=== BUILD-TRIGGER PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/** 快路径：编译 + marker → 批次立即发生，N=20 轮统计 marker→回调延迟。 */
	static void scenarioFastPath() throws Exception {
		System.out.println("\n--- BT1: marker triggers immediately (N=20) ---");
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String expected = "fast-" + i;
			compile(expected);
			CountDownLatch latch = armLatch();
			long t0 = System.nanoTime();
			writeMarker(System.currentTimeMillis());
			boolean done = latch.await(5, TimeUnit.SECONDS);
			check(done, "BT1." + i + ": batch must fire after marker");
			if (!done) return;
			long latencyMs = TimeUnit.NANOSECONDS.toMillis(lastBatchNanos.get() - t0);
			samples.add(latencyMs);
			checkEq(expected, call(), "BT1." + i + ": redefined value visible");
			sleepQuiet(60);
		}
		printLatency("BT1 marker->batch", samples);
		check(median(samples) <= 1500L, "BT1: median marker->batch latency must stay well below the fallback window");
		check(Collections.max(samples) <= 3000L, "BT1: max marker->batch latency must not look like a fallback wait");
	}

	/** 去触发对照：同一编译不写 marker → 只能等兜底窗口。 */
	static void scenarioFallbackControl() throws Exception {
		System.out.println("\n--- BT2: control without marker waits for the fallback window (N=3) ---");
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			String expected = "fallback-" + i;
			CountDownLatch latch = armLatch();
			long t0;
			compile(expected);
			t0 = System.nanoTime();
			boolean done = latch.await(5, TimeUnit.SECONDS);
			check(done, "BT2." + i + ": fallback batch must fire without a marker");
			if (!done) return;
			long latencyMs = TimeUnit.NANOSECONDS.toMillis(lastBatchNanos.get() - t0);
			samples.add(latencyMs);
			checkEq(expected, call(), "BT2." + i + ": redefined value visible");
			sleepQuiet(60);
		}
		printLatency("BT2 compile->batch (no marker)", samples);
		check(Collections.min(samples) >= 500L,
			"BT2: without a marker the batch must wait for the 800ms fallback window, got " + samples);
	}

	/** 换无关输入对照：只写 marker、不改文件 → 不产生批次、不自旋。 */
	static void scenarioUnrelatedMarker() throws Exception {
		System.out.println("\n--- BT3: marker without changed files must not trigger ---");
		sleepQuiet(300);
		// 先写一个 marker 推进 lastProcessedBuildEnd（它可能把上一轮非 marker 构建的文件补扫成 no-op 批次），
		// 再验证"没有新变更的 marker"不触发任何批次。
		writeMarker(System.currentTimeMillis());
		Thread.sleep(400L);
		int before = batchCount.get();
		String beforeValue = call();
		writeMarker(System.currentTimeMillis());
		Thread.sleep(400L);
		checkEq(beforeValue, call(), "BT3: value must not change");
		check(batchCount.get() == before,
			"BT3: an unrelated marker must not trigger a batch (before=" + before
			+ ", after=" + batchCount.get() + ")");
	}

	/** 取消兜底：兜底窗口设 3000ms，事件先到、marker 150ms 后到 → 必须在兜底 deadline 前触发。 */
	static void scenarioCancelsPendingFallback() throws Exception {
		System.out.println("\n--- BT4: marker cancels a pending fallback timer (window=3000ms) ---");
		int previous = RemoldAgent.getFileShakeMs();
		try {
			RemoldAgent.setFileShakeMs(3000);
			String expected = "cancel-me";
			compile(expected);
			CountDownLatch latch = armLatch();
			Thread.sleep(150L);
			long t0 = System.nanoTime();
			writeMarker(System.currentTimeMillis());
			boolean done = latch.await(5, TimeUnit.SECONDS);
			check(done, "BT4: marker must trigger before the 3000ms fallback deadline");
			long latencyMs = TimeUnit.NANOSECONDS.toMillis(lastBatchNanos.get() - t0);
			checkEq(expected, call(), "BT4: redefined value visible");
			check(latencyMs < 2000L,
				"BT4: marker->batch must be much earlier than the fallback window, was " + latencyMs + "ms");
			int after = batchCount.get();
			Thread.sleep(700L);
			check(batchCount.get() == after,
				"BT4: the canceled fallback must not fire a second batch (before=" + after
				+ ", after=" + batchCount.get() + ")");
		} finally {
			RemoldAgent.setFileShakeMs(previous);
		}
	}

	/** 构建中途：兜底先跑一批、marker 后到 → 只补余下变更，最终值正确。 */
	static void scenarioBuildInProgress() throws Exception {
		System.out.println("\n--- BT5: fallback batch may run mid-build; marker completes the rest ---");
		int previous = RemoldAgent.getFileShakeMs();
		try {
			RemoldAgent.setFileShakeMs(300);
			CountDownLatch first = armLatch();
			compile("mid-a");
			check(first.await(5, TimeUnit.SECONDS), "BT5: first change lands via the fallback window");
			checkEq("mid-a", call(), "BT5: first value visible");

			CountDownLatch second = armLatch();
			compile("mid-b");
			writeMarker(System.currentTimeMillis());
			check(second.await(5, TimeUnit.SECONDS), "BT5: marker batch lands the remaining change");
			checkEq("mid-b", call(), "BT5: second value visible");
			int after = batchCount.get();
			Thread.sleep(700L);
			check(batchCount.get() == after,
				"BT5: no duplicate non-empty batch after the marker batch (before=" + after
				+ ", after=" + batchCount.get() + ")");
		} finally {
			RemoldAgent.setFileShakeMs(previous);
		}
	}

	/** 陈旧 marker：只写一个 60s 前的 marker、不改文件 → 不触发批次。 */
	static void scenarioStaleMarker() throws Exception {
		System.out.println("\n--- BT6: stale marker must be ignored ---");
		sleepQuiet(400);
		int before = batchCount.get();
		writeMarker(System.currentTimeMillis() - 60_000L);
		Thread.sleep(400L);
		check(batchCount.get() == before,
			"BT6: stale marker must not trigger a batch (before=" + before
			+ ", after=" + batchCount.get() + ")");
	}

	// ---------------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------------

	static CountDownLatch armLatch() {
		CountDownLatch latch = new CountDownLatch(1);
		batchLatch = latch;
		return latch;
	}

	static void sleepQuiet(long millis) throws InterruptedException {
		batchLatch = new CountDownLatch(0);
		Thread.sleep(millis);
	}

	static String call() throws Exception {
		return (String) valueMethod.invoke(null);
	}

	/** 编译 {@code demo.Subject} 的指定版本到 classesDir（覆盖）。 */
	static void compile(String markerValue) throws IOException {
		if (markerValue.equals("v1")) {
			writeSource("v1");
		} else {
			writeSource(markerValue);
		}
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("no system java compiler");
		int rc = compiler.run(null, null, null,
			"-d", classesDir.toString(),
			"-g",
			srcDir.resolve("demo/Subject.java").toString());
		if (rc != 0) throw new IllegalStateException("javac failed for " + markerValue);
	}

	static void writeSource(String value) throws IOException {
		Path src = srcDir.resolve("demo/Subject.java");
		Files.createDirectories(src.getParent());
		String body = "package demo;\n"
			+ "public class Subject {\n"
			+ "    public static String value() { return \"" + value + "\"; }\n"
			+ "}\n";
		Files.write(src, body.getBytes(StandardCharsets.UTF_8));
		src.toFile().setLastModified(Instant.now().toEpochMilli());
	}

	/** 与 Gradle 插件同一格式写入 marker（临时文件 + 原子替换）。 */
	static void writeMarker(long endMillis) throws IOException {
		Files.createDirectories(marker.getParent());
		String buildId = "classes-" + endMillis + "-" + markerSeq.incrementAndGet();
		String content = "remold-build-trigger v1\n"
			+ "build_end_millis=" + endMillis + "\n"
			+ "build_id=" + buildId + "\n"
			+ "task=classes\n";
		Path tmp = marker.resolveSibling("build-trigger.tmp-" + markerSeq.get());
		Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void printLatency(String label, List<Long> samples) {
		if (samples.isEmpty()) return;
		List<Long> sorted = new ArrayList<>(samples);
		Collections.sort(sorted);
		System.out.println("   " + label + ": min=" + sorted.get(0) + "ms, median=" + median(sorted)
			+ "ms, max=" + sorted.get(sorted.size() - 1) + "ms, samples=" + samples);
	}

	static long median(List<Long> sorted) {
		int n = sorted.size();
		if (n == 0) return -1L;
		if (n % 2 == 1) return sorted.get(n / 2);
		return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2L;
	}

	static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException ignored) {
				}
			});
		}
	}
}

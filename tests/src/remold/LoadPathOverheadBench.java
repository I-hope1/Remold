import remold.vcs.LoadFrontier;
import remold.vcs.Changeset;
import remold.reflect.mixin.MixinTargetIndex;
import remold.ref.PendingLoadPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 加载钩子路径上新增操作的逐类开销（**代理测量**，不是真机冷启动读数）。
 *
 * <p>回答的问题是"叠加之后每个类多花多少 ns"：{@code LoadFrontier.birth}、
 * {@code MixinTargetIndex.lookup}、{@code PendingLoadPatch.decide} 三者单独与合并的
 * min/p50/max。真机冷启动与懒加载掉帧不在本文件范围内（那需要 KnotServer 起停对照，
 * 见 {@code tests/fabric-mixin-probe/}）。</p>
 *
 * <p>运行：{@code ./gradlew :tests:testClasses} 后
 * {@code java -cp <core 输出>:<tests 输出> remold.LoadPathOverheadBench}。</p>
 */
public final class LoadPathOverheadBench {

	public static void main(String[] args) {
		int n = 3000;
		// 预热
		for (int i = 0; i < 20_000; i++) {
			LoadFrontier.birth(1L, "bench/C" + (i % 64), "");
			MixinTargetIndex.lookup("bench/C" + (i % 64));
			PendingLoadPatch.decide(1L, "bench/C" + (i % 64), new byte[] {1}, true, (n2, b) -> b);
		}
		System.out.println("=== per-op cost over " + n + " classes (ns/op) ===");
		report("LoadFrontier.birth", n, i -> LoadFrontier.birth(1L, "bench/C" + i, ""));
		report("MixinTargetIndex.lookup", n, i -> MixinTargetIndex.lookup("bench/C" + i));
		report("PendingLoadPatch.decide", n, i ->
			PendingLoadPatch.decide(1L, "bench/C" + i, new byte[] {1}, true, (n2, b) -> b));
		report("all three (current hook path)", n, i -> {
			LoadFrontier.birth(1L, "bench/C" + i, "");
			MixinTargetIndex.lookup("bench/C" + i);
			PendingLoadPatch.decide(1L, "bench/C" + i, new byte[] {1}, true, (n2, b) -> b);
		});
		System.out.println("watermark=" + LoadFrontier.watermark()
		 + " dropped=" + LoadFrontier.droppedBirths()
		 + " conservativeOnly=" + LoadFrontier.conservativeOnly());
	}

	private interface Op { void run(int i); }

	private static void report(String label, int n, Op op) {
		List<Long> perOp = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			long t0 = System.nanoTime();
			op.run(i);
			perOp.add(System.nanoTime() - t0);
		}
		Collections.sort(perOp);
		long total = 0;
		for (long v : perOp) total += v;
		System.out.printf("%-34s min=%5d p50=%5d max=%7d mean=%7.1f ns%n",
		 label, perOp.get(0), perOp.get(n / 2), perOp.get(n - 1), total / (double) n);
	}
}

package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布局门的<b>批次级存活探测</b>门禁（{@code docs/initfix/05-jvmti-heap.md} §「批次存在性探测」）。
 *
 * <p>布局门只在「布局不兼容」的类上问 liveness，而每次 liveness 都是一次全堆遍历：同一批 50 个
 * 零实例类就是 50 次全堆扫描。批次上下文声明后，第一次未命中缓存时必须用一次遍历把整批填满，
 * 后续同批类直接命中缓存。</p>
 *
 * <p>用例结构是「机制做了这件事」与「别的原因也产生了同一个信号」的对照：</p>
 * <ol>
 *   <li>设了批次上下文：K 个类的第一次查询只增加 <b>1</b> 次遍历，后续查询 <b>0</b> 次；</li>
 *   <li>不设批次上下文（去触发）：同样的查询序列每次各增加 1 次遍历——证明"1 次"来自批预探测，
 *       不是缓存/别处顺手扫掉的；</li>
 *   <li>批探测结果与逐类探测结果逐项相等（换无关输入：把零实例类换成有实例类，判定必须跟着变）。</li>
 * </ol>
 */
class BatchLivenessPrefetchTest {

	static class LiveA { }

	static class LiveB { }

	static class DeadC { }

	/** 从未被实例化：用于"零实例"断言，避免受同一 JVM 里其它用例残留实例影响。 */
	static class NeverLive { }

	private Map<String, Class<?>> savedLoadedClasses;

	@BeforeEach
	void setUp() {
		savedLoadedClasses = new LinkedHashMap<>(RemoldAgent.loadedClassesMap);
		RemoldAgent.loadedClassesMap.put(LiveA.class.getName(), LiveA.class);
		RemoldAgent.loadedClassesMap.put(LiveB.class.getName(), LiveB.class);
		RemoldAgent.loadedClassesMap.put(DeadC.class.getName(), DeadC.class);
		RemoldAgent.resetLiveInstanceCache();
		RemoldAgent.setLivenessBatch(Set.of());
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.setLivenessBatch(Set.of());
		RemoldAgent.resetLiveInstanceCache();
		RemoldAgent.loadedClassesMap.clear();
		RemoldAgent.loadedClassesMap.putAll(savedLoadedClasses);
	}

	@Test
	void oneBatchWalkCoversTheWholeRound() {
		List<Object> keepAlive = new ArrayList<>();
		keepAlive.add(new LiveA());
		keepAlive.add(new LiveB());
		assertEquals(2, keepAlive.size());

		RemoldAgent.setLivenessBatch(List.of(LiveA.class.getName(), LiveB.class.getName(), DeadC.class.getName()));

		long before = LibTool.heapWalkCount();
		assertTrue(LibTool.heapWalkCount() >= 0, "native heap-walk counter unavailable (old libtool build)");
		assertTrue(RemoldAgent.hasLiveInstances(LiveA.class), "LiveA has a live instance");
		long afterFirst = LibTool.heapWalkCount();
		assertTrue(RemoldAgent.hasLiveInstances(LiveB.class), "LiveB has a live instance");
		assertFalse(RemoldAgent.hasLiveInstances(DeadC.class), "DeadC has no live instance");
		long afterAll = LibTool.heapWalkCount();

		if (LibTool.batchScanIsNative()) {
			assertEquals(1L, afterFirst - before,
				"the first liveness query must fill the whole batch with a single heap walk");
			assertEquals(afterFirst, afterAll,
				"the remaining classes of the same batch must hit the cache (zero extra walks)");
		} else {
			// 旧 native 库：退回逐类探测，判定方向不变，只是每个类各一次遍历（D2 降级契约）。
			assertEquals(3L, afterAll - before, "without the native batch entry each class costs one walk");
		}
		assertEquals(2, keepAlive.size());
	}

	@Test
	void withoutBatchContextEveryQueryScansSeparately() {
		List<Object> keepAlive = new ArrayList<>();
		keepAlive.add(new LiveA());
		keepAlive.add(new LiveB());

		// 去触发对照：不声明批次上下文，同一序列必须每类各扫一次。
		long before = LibTool.heapWalkCount();
		assertTrue(RemoldAgent.hasLiveInstances(LiveA.class));
		assertTrue(RemoldAgent.hasLiveInstances(LiveB.class));
		assertFalse(RemoldAgent.hasLiveInstances(DeadC.class));
		long used = LibTool.heapWalkCount() - before;

		assertEquals(3L, used, "without a batch context every liveness query must cost its own heap walk");
		assertEquals(2, keepAlive.size());
	}

	@Test
	void batchProbeAgreesWithPerClassProbe() {
		List<Object> keepAlive = new ArrayList<>();
		keepAlive.add(new LiveA());

		Class<?>[] targets = { LiveA.class, NeverLive.class, DeadC.class };
		boolean[] batch = LibTool.hasLiveInstancesBatch(targets);
		assertEquals(3, batch.length);
		for (int i = 0; i < targets.length; i++) {
			assertEquals(LibTool.hasLiveInstance(targets[i]), batch[i],
				"batch liveness must equal the single-class probe for " + targets[i].getSimpleName());
		}
		assertTrue(batch[0], "LiveA has a live instance");
		assertFalse(batch[1], "NeverLive was never instantiated");
		assertFalse(batch[2], "DeadC was never instantiated");
		assertEquals(1, keepAlive.size());
	}
}

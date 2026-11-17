import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次级单次全堆遍历的<b>等价性</b>门禁（{@code docs/initfix/05-jvmti-heap.md} §「批次实例收集」）。
 *
 * <p>为什么等价性是第一道门：{@code LibTool.getInstancesBatch} 把「同一批 K 个类的实例收集」
 * 从 K 次全堆遍历收敛为 1 次，归类靠的是 JVMTI 的 class tag。class tag 只认对象的<b>精确运行时类</b>
 * ——一旦实现里只给「直接列在 targets 里的类」打 tag，{@code Sub extends Base} 中
 * {@code Base} 的结果就会静默丢掉 {@code Sub} 实例，{@code @RemoldReinit(INCLUDE_SUBCLASSES)}
 * 与 InitFix 的子类覆盖契约随之失效，而且不会抛任何异常。因此这里逐项比对
 * 「批收集各 target 的实例集合」与「逐类 {@link LibTool#getInstances(Class)} 的实例集合」。</p>
 *
 * <p>夹具覆盖：同批多类、<b>不在 targets 里的已加载子类/实现类</b>（{@code SubSub}、{@code ImplB}）、
 * 零/非零混居、K=1/3/64 三种规模。断言口径是<b>集合比较</b>（遍历顺序不保证一致），
 * 元素身份用 {@link IdentityHashMap} 语义比较（同一对象，而不是 equals）。</p>
 *
 * <p>遍历次数：{@link LibTool#heapWalkCount()} 是 native 侧的真实计数。native 库带批次入口时
 * K 个 target 必须只产生 1 次遍历；旧库（缺导出符号）按设计整体退回逐类路径，此时断言退化为
 * K 次遍历 + 结果仍逐项一致——两条分支都有断言，不存在"什么都不检查"的空跑。</p>
 *
 * <p>绝对计数刻意<b>不</b>参与断言：同一个 JVM 里先前用例创建的实例可能还没被 GC 回收，
 * 堆遍历会照样看见它们。所有"必须包含"的断言都落在本用例自己创建的那些对象上。</p>
 */
class BatchHeapScanEquivalenceTest {

	// ------------------------------------------------------------------ fixtures

	static class Base {
		final int v;
		Base(int v) { this.v = v; }
	}

	static class Sub extends Base {
		Sub(int v) { super(v); }
	}

	/** 刻意<b>不</b>列进任何 targets：它的实例必须仍然出现在 Base / Sub 的结果里。 */
	static class SubSub extends Sub {
		SubSub(int v) { super(v); }
	}

	interface Iface { }

	static class ImplA implements Iface { }

	/** 刻意<b>不</b>列进 targets：它的实例必须仍然出现在 Iface 的结果里。 */
	static class ImplB implements Iface { }

	/** 零存活实例：批收集必须给出空集合，且不能因此多跑一次遍历。 */
	static class Zero { }

	/** 一次用例创建的全部实例（强引用保持到用例结束，避免被 GC 提前回收）。 */
	record Fixtures(List<Object> bases, List<Object> subs, List<Object> subSubs,
	                List<Object> implAs, List<Object> implBs, List<Object> all) { }

	private static Fixtures makeInstances() {
		List<Object> bases = new ArrayList<>();
		List<Object> subs = new ArrayList<>();
		List<Object> subSubs = new ArrayList<>();
		List<Object> implAs = new ArrayList<>();
		List<Object> implBs = new ArrayList<>();
		for (int i = 0; i < 5; i++) bases.add(new Base(i));
		for (int i = 0; i < 7; i++) subs.add(new Sub(i));
		for (int i = 0; i < 3; i++) subSubs.add(new SubSub(i));
		for (int i = 0; i < 4; i++) implAs.add(new ImplA());
		for (int i = 0; i < 2; i++) implBs.add(new ImplB());
		List<Object> all = new ArrayList<>();
		all.addAll(bases);
		all.addAll(subs);
		all.addAll(subSubs);
		all.addAll(implAs);
		all.addAll(implBs);
		return new Fixtures(bases, subs, subSubs, implAs, implBs, all);
	}

	private static Set<Object> identitySet(Object[] values) {
		Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Object v : values) {
			assertNotNull(v, "instance array must not contain null slots");
			set.add(v);
		}
		return set;
	}

	/** 逐类 {@code getInstances} 的基准：把结果按对象身份放进集合。 */
	private static Set<Object> perClass(Class<?> target) {
		return identitySet(LibTool.getInstances(target));
	}

	private static long walks() {
		long count = LibTool.heapWalkCount();
		assertTrue(count >= 0, "native heap-walk counter is unavailable (old libtool build);"
			+ " the batch-scan equivalence gate needs a library with nHeapWalkCount");
		return count;
	}

	// ------------------------------------------------------------------ cases

	@Test
	void batchResultsMatchPerClassResultsIncludingUnlistedSubclasses() {
		Fixtures fx = makeInstances();
		Class<?>[] targets = { Base.class, Sub.class, Zero.class };

		Object[][] batch = LibTool.getInstancesBatch(targets);

		assertEquals(targets.length, batch.length, "batch result must stay aligned with the targets array");
		for (int i = 0; i < targets.length; i++) {
			assertEquals(perClass(targets[i]), identitySet(batch[i]),
				"batch collection for " + targets[i].getSimpleName() + " must equal per-class getInstances");
		}
		// 前置基线：结果非空，且确实包含"不在 targets 里的子类"实例——否则下面的等价性是空跑。
		Set<Object> baseSet = identitySet(batch[0]);
		assertTrue(baseSet.containsAll(fx.bases()), "Base bucket must contain its own instances");
		assertTrue(baseSet.containsAll(fx.subs()), "Base bucket must contain Sub instances");
		assertTrue(baseSet.containsAll(fx.subSubs()), "Base bucket must contain SubSub instances (not a target)");
		assertEquals(0, batch[2].length, "Zero has no live instance");
	}

	@Test
	void subclassInstancesLandInBothAncestorAndOwnBucket() {
		Fixtures fx = makeInstances();
		Object[][] batch = LibTool.getInstancesBatch(new Class<?>[]{ Base.class, Sub.class });
		Set<Object> baseSet = identitySet(batch[0]);
		Set<Object> subSet = identitySet(batch[1]);

		// D1：Sub extends Base 同批出现时，Sub 实例必须同时出现在两份结果里。
		for (Object o : fx.subs()) {
			assertTrue(baseSet.contains(o), "Sub instance must appear in the Base bucket");
			assertTrue(subSet.contains(o), "Sub instance must appear in the Sub bucket");
		}
		// 多态等价性：不在 targets 里的已加载子类 SubSub 也必须落到祖先桶里。
		for (Object o : fx.subSubs()) {
			assertTrue(baseSet.contains(o), "SubSub (not a target) must still appear in the Base bucket");
			assertTrue(subSet.contains(o), "SubSub (not a target) must still appear in the Sub bucket");
		}
		// 反向对照：Base 自己的实例不能漏进 Sub 桶（否则 Sub 桶就是"所有 Base 实例"的误配）。
		for (Object o : fx.bases()) {
			assertFalse(subSet.contains(o), "plain Base instance must not appear in the Sub bucket");
		}
	}

	@Test
	void interfaceTargetSeesLoadedImplementationsThatAreNotTargets() {
		Fixtures fx = makeInstances();
		Object[][] batch = LibTool.getInstancesBatch(new Class<?>[]{ Iface.class, ImplA.class });
		Set<Object> ifaceSet = identitySet(batch[0]);
		Set<Object> implASet = identitySet(batch[1]);

		assertEquals(perClass(Iface.class), ifaceSet);
		assertEquals(perClass(ImplA.class), implASet);
		for (Object o : fx.implAs()) {
			assertTrue(ifaceSet.contains(o), "ImplA instance must appear in the Iface bucket");
			assertTrue(implASet.contains(o), "ImplA instance must appear in the ImplA bucket");
		}
		for (Object o : fx.implBs()) {
			assertTrue(ifaceSet.contains(o), "ImplB (not a target) must appear in the Iface bucket");
			assertFalse(implASet.contains(o), "ImplB must not appear in the ImplA bucket");
		}
	}

	@Test
	void singleTargetTakesTheOriginalPerClassPath() {
		makeInstances();
		long before = walks();
		Object[][] batch = LibTool.getInstancesBatch(new Class<?>[]{ Base.class });
		long used = walks() - before;

		assertEquals(1, batch.length);
		assertEquals(perClass(Base.class), identitySet(batch[0]));
		// K=1 直走原单类路径：恰好一次遍历，不多不少（零额外开销）。
		assertEquals(1L, used, "K=1 must cost exactly one heap walk");
	}

	@Test
	void heapWalkCountCollapsesFromKToOneWhenBatchScanIsNative() {
		Fixtures fx = makeInstances();
		Class<?>[] targets = paddedTargets(64);

		long beforeBatch = walks();
		Object[][] batch = LibTool.getInstancesBatch(targets);
		long batchWalks = walks() - beforeBatch;

		long beforePerClass = walks();
		List<Object[]> perClass = new ArrayList<>();
		for (Class<?> target : targets) perClass.add(LibTool.getInstances(target));
		long perClassWalks = walks() - beforePerClass;

		assertEquals(targets.length, perClassWalks, "per-class path must cost one walk per class");
		if (LibTool.batchScanIsNative()) {
			// 一次 native 批查询最多 BATCH_TARGET_LIMIT 个 target（对象 tag 槽位 / 检索 tag 数预算，
			// 见 docs/initfix/05-jvmti-heap.md）：K 更大时按块切分，故期望值是 ceil(K / LIMIT)。
			long expected = Math.max(1, (targets.length + LibTool.BATCH_TARGET_LIMIT - 1)
			                            / LibTool.BATCH_TARGET_LIMIT);
			assertEquals(expected, batchWalks, "batch collection must collapse K=" + targets.length
				+ " heap walks into ceil(K/" + LibTool.BATCH_TARGET_LIMIT + ") walks");
			assertTrue(batchWalks < targets.length,
			           "batch collection must be strictly cheaper than one walk per class");
			// 单块内的"K 次 → 1 次"契约单独钉死：这一条是文档里承诺的对外语义。
			long beforeSingleChunk = walks();
			LibTool.getInstancesBatch(Arrays.copyOf(targets, LibTool.BATCH_TARGET_LIMIT));
			assertEquals(1L, walks() - beforeSingleChunk,
			             "a single chunk of " + LibTool.BATCH_TARGET_LIMIT + " targets must cost exactly one walk");
		} else {
			// 旧 native 库：按设计整体退回逐类路径，行为仍然正确（D2 的降级契约）。
			assertEquals((long) targets.length, batchWalks,
				"without the native batch entry the API must fall back to one walk per class");
		}
		for (int i = 0; i < targets.length; i++) {
			assertEquals(identitySet(perClass.get(i)), identitySet(batch[i]),
				"batch and per-class results must match for target #" + i);
		}
		// 非空基线：这一轮确实有存活实例参与归类，等价性断言不是"两个空集合相等"。
		assertTrue(identitySet(batch[0]).containsAll(fx.bases()), "Base bucket must be populated");
	}

	@Test
	void repeatedBatchScansAreStableAndLeaveNoDirtyTags() {
		Fixtures fx = makeInstances();
		Class<?>[] targets = { Base.class, Sub.class, Iface.class, Zero.class };

		Set<Object>[] first = new Set[targets.length];
		Object[][] firstBatch = LibTool.getInstancesBatch(targets);
		for (int i = 0; i < targets.length; i++) first[i] = identitySet(firstBatch[i]);
		assertTrue(first[0].containsAll(fx.bases()), "baseline: the Base bucket must be populated");

		// 临时 class_tag 若没被完整清零，第二次遍历/逐类查询就会命中错对象：重复调用必须完全一致。
		Object[][] secondBatch = LibTool.getInstancesBatch(targets);
		for (int i = 0; i < targets.length; i++) {
			assertEquals(first[i], identitySet(secondBatch[i]), "batch scan must be idempotent for target #" + i);
		}
		for (int i = 0; i < targets.length; i++) {
			assertEquals(first[i], perClass(targets[i]),
				"per-class query after a batch scan must still match (no dirty tags left behind) for target #" + i);
		}
	}

	@Test
	void liveInstanceBatchProbeMatchesPerClassProbe() {
		makeInstances();
		Class<?>[] targets = { Base.class, Sub.class, SubSub.class, Iface.class, ImplA.class, ImplB.class, Zero.class };

		boolean[] batch = LibTool.hasLiveInstancesBatch(targets);

		assertEquals(targets.length, batch.length);
		for (int i = 0; i < targets.length; i++) {
			assertEquals(LibTool.hasLiveInstance(targets[i]), batch[i],
				"batch liveness probe must equal the single-class probe for " + targets[i].getSimpleName());
		}
		assertFalse(batch[targets.length - 1], "Zero must report no live instance");
	}

	@Test
	void emptyAndNullTargetsAreHandled() {
		assertEquals(0, LibTool.getInstancesBatch(new Class<?>[0]).length);
		assertEquals(0, LibTool.getInstancesBatch(null).length);
		assertEquals(0, LibTool.hasLiveInstancesBatch(new Class<?>[0]).length);
	}

	// ------------------------------------------------------------------ helpers

	/** 把真实夹具类补到 {@code size} 个 target（重复项无害：归类与计数都不依赖唯一性）。 */
	private static Class<?>[] paddedTargets(int size) {
		Class<?>[] pool = { Base.class, Sub.class, SubSub.class, Iface.class, ImplA.class, ImplB.class, Zero.class };
		Class<?>[] targets = new Class<?>[size];
		for (int i = 0; i < size; i++) targets[i] = pool[i % pool.length];
		return targets;
	}
}

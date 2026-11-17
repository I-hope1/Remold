package remold;

import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LibTool JVMTI 本地实例扫描与引用分析实测。
 */
public class LibToolTest {

	static class TargetDummy {
		final String tag;
		TargetDummy(String tag) { this.tag = tag; }
	}

	/** Subclass used to pin down whether a heap query is exact-class or class-plus-subclasses. */
	static class SubDummy extends TargetDummy {
		SubDummy(String tag) { super(tag); }
	}

	static class DummyHolder {
		final TargetDummy target;
		DummyHolder(TargetDummy target) { this.target = target; }
	}

	static final class BulkDummy {
		final int id;
		BulkDummy(int id) { this.id = id; }
	}

	@Test
	void nativeLibraryInitialized() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		assertTrue(LibTool.initialized(), "LibTool native library must be initialized");
		String osName = System.getProperty("os.name");
		String osArch = System.getProperty("os.arch");
		boolean nativeBucketPlan = LibTool.enumerationBucketPlanIsNative();
		int bucketWindow = LibTool.enumerationBucketWindow();
		System.out.println("[LibToolTest] os.name=" + osName
			+ ", os.arch=" + osArch
			+ ", nativeBucketPlan=" + nativeBucketPlan
			+ ", bucketWindow=" + bucketWindow);
		String expectedArch = System.getProperty("hstest.expectedOsArch");
		if (expectedArch != null && !expectedArch.isEmpty()) {
			assertEquals(expectedArch, osArch, "Test JVM os.arch mismatch");
		}
		if (Boolean.getBoolean("hstest.requireNativeBucketPlan")) {
			assertTrue(nativeBucketPlan, "Expected native enumeration bucket plan (not Java fallback)");
			assertEquals(1 << 18, bucketWindow, "Expected native enumeration bucket window 2^18");
		}
	}

	@Test
	void heapScanFindsLiveInstances() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		TargetDummy d1 = new TargetDummy("first");
		TargetDummy d2 = new TargetDummy("second");

		TargetDummy[] instances = LibTool.getInstances(TargetDummy.class);
		assertNotNull(instances, "getInstances must not return null");
		assertTrue(instances.length >= 2, "Expected at least 2 instances, got: " + instances.length);
		assertTrue(Arrays.asList(instances).contains(d1), "Instances must contain d1");
		assertTrue(Arrays.asList(instances).contains(d2), "Instances must contain d2");
	}

	@Test
	void getReferrersFindsHoldingObject() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		TargetDummy target = new TargetDummy("referenced");
		DummyHolder holder = new DummyHolder(target);

		Object[] referrers = LibTool.getReferrers(target);
		assertNotNull(referrers, "getReferrers must not return null");
		assertTrue(Arrays.asList(referrers).contains(holder), "Referrers must contain holder object");
	}

	@Test
	void primitiveAndArrayTypesRejected() {
		assertThrows(IllegalArgumentException.class, () -> LibTool.getInstances(int.class));
		assertThrows(IllegalArgumentException.class, () -> LibTool.getInstances(String[].class));
	}

	/**
	 * Pins down the match scope of a heap query: {@code getInstances(P)} returns P's instances
	 * <b>and</b> those of its subclasses, while {@code getInstances(C)} never returns parent instances.
	 *
	 * <p>This is not cosmetic. {@code InitFix} patches every object the query returns, so a query that
	 * silently widened or narrowed would either write into objects of the wrong class or skip
	 * surviving instances. The pending "single heap traversal, dispatch by class" optimisation also
	 * has to reproduce exactly this asymmetry, which is why it is asserted here rather than inferred
	 * from the Javadoc.</p>
	 */
	@Test
	void heapScanIncludesSubclassesButNotSuperclasses() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		TargetDummy base = new TargetDummy("parent");
		SubDummy sub = new SubDummy("child");

		TargetDummy[] fromParent = LibTool.getInstances(TargetDummy.class);
		assertTrue(Arrays.asList(fromParent).contains(base), "querying a parent must return its own instances");
		assertTrue(Arrays.asList(fromParent).contains(sub),
			"querying a parent must also return instances of subclasses (LibTool contract)");

		TargetDummy[] fromChild = LibTool.getInstances(SubDummy.class);
		assertTrue(Arrays.asList(fromChild).contains(sub), "querying a subclass must return its own instances");
		assertFalse(Arrays.asList(fromChild).contains(base),
			"querying a subclass must NOT return parent instances: the patch loop would otherwise"
			+ " write into objects whose class was never redefined");
	}

	@Test
	void clinitAutomaticallyInitializesLibTool() {
		assertTrue(LibTool.initialized(), "LibTool static initializer must initialize native library automatically");
	}

	@Test
	void perfLogEmitsPhaseBreakdownForGetInstances() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		TargetDummy a = new TargetDummy("a");
		TargetDummy b = new TargetDummy("b");
		List<String> infos = new ArrayList<>();
		RemoldAgent.Logger savedLogger = RemoldAgent.logger;
		boolean savedPerfLog = RemoldAgent.PERF_LOG;
		try {
			RemoldAgent.logger = new RemoldAgent.Logger() {
				@Override public void log(String msg) {}
				@Override public void info(String msg) { infos.add(msg); }
				@Override public void warn(String msg) {}
				@Override public void error(String msg) {}
				@Override public void error(String msg, Throwable t) {}
			};

			RemoldAgent.PERF_LOG = false;
			LibTool.getInstances(TargetDummy.class);
			assertTrue(infos.stream().noneMatch(m -> m.contains("[Lib] getInstances(")),
				"breakdown must not log when PERF_LOG is false");

			RemoldAgent.PERF_LOG = true;
			TargetDummy[] found = LibTool.getInstances(TargetDummy.class);
			assertTrue(Arrays.asList(found).containsAll(List.of(a, b)),
				"timed path must return all live instances");
			String line = infos.stream()
				.filter(m -> m.contains("[Lib] getInstances(" + TargetDummy.class.getName() + "):"))
				.findFirst()
				.orElse(null);
			assertNotNull(line, "PERF_LOG=true must emit phase breakdown log, got: " + infos);
			assertTrue(line.contains("validate=")
				&& line.contains("iterate=")
				&& line.contains("collect=")
				&& line.contains("alloc=")
				&& line.contains("fill=")
				&& line.contains("cleanup="),
				"breakdown line missing phase fields: " + line);
		} finally {
			RemoldAgent.PERF_LOG = savedPerfLog;
			RemoldAgent.logger = savedLogger;
		}
	}

	@Test
	void largeQueryClearsTagsAndRecoversForSubsequentQueries() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		// > 2^18 (262_144) exercises both multi-bucket counting sort (>=2 buckets) and > 32768 clearTagRange.
		final int expectedCount = 550_000;
		List<BulkDummy> held = new ArrayList<>(expectedCount);
		for (int i = 0; i < expectedCount; i++) {
			held.add(new BulkDummy(i));
		}

		BulkDummy[] first = LibTool.getInstances(BulkDummy.class);
		assertEquals(expectedCount, first.length,
			"first large query must return all live instances without holes or duplicates");
		BitSet seen = new BitSet(expectedCount);
		for (BulkDummy d : first) {
			assertNotNull(d, "multi-bucket scatter must not leave null holes");
			assertFalse(seen.get(d.id), "duplicate instance id returned: " + d.id);
			seen.set(d.id);
		}
		assertEquals(expectedCount, seen.cardinality(), "all 550k ids must be present");

		// Second consecutive multi-bucket query verifies warmed tag-map reuse across bulk tag clears.
		BulkDummy[] second = LibTool.getInstances(BulkDummy.class);
		assertEquals(expectedCount, second.length,
			"second large query after bulk tag clear must still return all live instances");

		assertTrue(LibTool.hasLiveInstance(BulkDummy.class), "hasLiveInstance must keep working");
		TargetDummy small = new TargetDummy("after-dispose");
		DummyHolder holder = new DummyHolder(small);
		assertTrue(Arrays.asList(LibTool.getInstances(TargetDummy.class)).contains(small));
		assertTrue(Arrays.asList(LibTool.getReferrers(small)).contains(holder));
		assertFalse(held.isEmpty());
	}
}

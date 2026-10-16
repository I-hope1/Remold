package remold;

import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LibTool JVMTI 本地实例扫描与引用分析实测。
 */
public class LibToolTest {

	static class TargetDummy {
		final String tag;
		TargetDummy(String tag) { this.tag = tag; }
	}

	static class DummyHolder {
		final TargetDummy target;
		DummyHolder(TargetDummy target) { this.target = target; }
	}

	@Test
	void nativeLibraryInitialized() {
		if (!LibTool.initialized()) {
			LibTool.init();
		}
		assertTrue(LibTool.initialized(), "LibTool native library must be initialized");
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
}

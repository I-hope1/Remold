package remold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 三态日志是**接口**：探针会拿它做前置断言，所以格式必须被钉住，不能靠"以后没人改文案"的假设。
 */
class TypeChangedFieldLogFormatTest {

	@Test
	void theStateLineHasAFixedPrefixAndKeyValuePairs() {
		String line = remold.ref.InitFix.typeChangedState("a/B", "f", "rejected", "ctor-param-assign");
		assertEquals("[TYPE-CHANGED-FIELD] cls=a/B field=f state=rejected reason=ctor-param-assign", line,
			"probes parse this line; changing it silently breaks every precondition assertion built on it");
	}

	@Test
	void rawReasonsAreNormalizedToEnums() {
		assertEquals("ctor-param-assign",
			remold.ref.InitFix.typeChangedReason("field written outside its extraction in a/B.<init> at index 4"));
		assertEquals("no-initializer",
			remold.ref.InitFix.typeChangedReason("no safe initialization expression found in constructors"));
		assertEquals("none", remold.ref.InitFix.typeChangedReason(null),
			"an accepted field has no reason; it must not print null");
	}

	@Test
	void theReasonEnumSetIsPinnedAndOtherIsNotAValidOutcome() {
		assertEquals(java.util.Set.of("none", "ctor-param-assign", "no-initializer", "later-gate",
				"type-unchanged", "key-mismatch", "reinit-owned", "other"),
			remold.ref.InitFix.TYPE_CHANGED_REASONS,
			"adding a reason must be done in the constant AND in this test, so no probe silently accepts a new label");
		assertEquals("other", remold.ref.InitFix.typeChangedReason("something nobody classified"),
			"other is the fallback; the probe framework treats it as a failure, so it must stay reachable only here");
	}

	@Test
	void theLineCarriesNoInstanceText() {
		String line = remold.ref.InitFix.typeChangedState("a/B", "f", "accepted", "none");
		assertEquals(-1, line.indexOf('@'),
			"an instance rendered into the log would mean toString() ran on a class we are patching");
	}
}

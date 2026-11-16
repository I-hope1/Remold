package remold.ref;

import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HeapFieldWatchUnitTest {
	static volatile Object keepAlive;

	static class BaseWithInheritedPrivate {
		@SuppressWarnings("unused")
		private final String secret;
		@SuppressWarnings("unused")
		private static final String STATIC_ONLY = "static-value";

		BaseWithInheritedPrivate(String secret) {
			this.secret = secret;
		}
	}

	static class ChildShadowing extends BaseWithInheritedPrivate {
		// Shadows superclass private field with same name and different value.
		@SuppressWarnings("unused")
		private final String secret;

		ChildShadowing(String baseSecret, String childSecret) {
			super(baseSecret);
			this.secret = childSecret;
		}
	}

	static final class WithConditions {
		@SuppressWarnings("unused")
		final Object value = null;
		@SuppressWarnings("unused")
		final String conditions = "cond-list";
	}

	@Test
	void scanReadsInheritedPrivateFieldAgreeingWithFieldHandleAndIgnoresSubclassShadowAndStatic() {
		assumeTrue(LibTool.initialized(), "LibTool JVMTI native library required");

		BaseWithInheritedPrivate baseNull = new BaseWithInheritedPrivate(null);
		BaseWithInheritedPrivate baseVal = new BaseWithInheritedPrivate("ok");
		ChildShadowing child = new ChildShadowing("from-base", "from-child");
		List<Object> liveKeepAlive = Arrays.asList(baseNull, baseVal, child);

		HeapFieldWatch.Snapshot inheritedSnap = HeapFieldWatch.scan(
			"inherited-check", BaseWithInheritedPrivate.class, "secret");
		assertEquals(3, inheritedSnap.total);
		assertEquals(1, inheritedSnap.nullCount);
		assertEquals(0, inheritedSnap.absent);
		assertEquals(0, inheritedSnap.unreadable);
		assertEquals(3, inheritedSnap.channelAgree,
			"inherited private field lookup must use declaring class and match on subclass instances too");
		assertEquals(0, inheritedSnap.channelConflict,
			"subclass shadowing field must not produce a false channel conflict");
		assertTrue(inheritedSnap.firstNull.contains("class=" + BaseWithInheritedPrivate.class.getName()),
			"firstNull without a conditions field must report instance class, got: " + inheritedSnap.firstNull);
		assertFalse(inheritedSnap.firstNull.contains("conditions="),
			"firstNull must not fabricate conditions=null when the class has no conditions field");

		HeapFieldWatch.Snapshot staticOnlySnap = HeapFieldWatch.scan(
			"static-ignore-check", BaseWithInheritedPrivate.class, "STATIC_ONLY");
		assertEquals(3, staticOnlySnap.total);
		assertEquals(3, staticOnlySnap.absent, "static field must not be treated as an instance field");
		assertEquals(0, staticOnlySnap.nullCount);
		assertEquals(0, staticOnlySnap.channelConflict);
		HeapFieldWatch.Snapshot childSnap = HeapFieldWatch.scan(
			"child-shadow-check", ChildShadowing.class, "secret");
		assertEquals(1, childSnap.total);
		assertEquals(0, childSnap.nullCount);
		assertEquals(1, childSnap.channelAgree);
		assertEquals(0, childSnap.channelConflict);
		keepAlive = liveKeepAlive;
	}

	@Test
	void firstNullRetainsConditionsDetailWhenConditionsFieldExists() {
		assumeTrue(LibTool.initialized(), "LibTool JVMTI native library required");

		WithConditions inst = new WithConditions();
		HeapFieldWatch.Snapshot snap = HeapFieldWatch.scan("cond-check", WithConditions.class, "value");
		assertEquals(1, snap.total);
		assertEquals(1, snap.nullCount);
		assertTrue(snap.firstNull.contains("conditions=cond-list"),
			"firstNull must preserve conditions detail when present, got: " + snap.firstNull);
		keepAlive = inst;
	}
}

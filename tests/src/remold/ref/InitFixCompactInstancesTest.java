package remold.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InitFixCompactInstancesTest {

	@Test
	void compactNonNullAvoidsCopyWhenCleanAndFiltersNullSlots() {
		assertEquals(0, InitFix.compactNonNull(null).length);
		assertEquals(0, InitFix.compactNonNull(new Object[0]).length);
		assertEquals(0, InitFix.compactNonNull(new Object[] {null, null}).length);

		Object[] clean = new Object[] {"a", "b"};
		assertSame(clean, InitFix.compactNonNull(clean),
			"non-null instance array must be reused directly without extra copying");

		Object[] sparse = new Object[] {null, "x", null, "y", null};
		assertArrayEquals(new Object[] {"x", "y"}, InitFix.compactNonNull(sparse));
	}
}

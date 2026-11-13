import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import remold.LambdaAligner;

/**
 * 常量折叠：long 移位的右操作数按 JLS 固定是 int（K_INT），必须能折叠，
 * 否则 bodyFingerprint 在 `1L << 3` 与 `8L` 之间判不等，等价证明失效。
 */
class LambdaFoldTest {

	private static final int K_INT = 1;
	private static final int K_LONG = 2;

	@Test
	void longShiftFoldsWithIntDistance() {
		assertEquals(Long.valueOf(8L), LambdaAligner.foldBinaryForTest(K_LONG, 1L, K_INT, 3, Opcodes.LSHL));
		assertEquals(Long.valueOf(1L), LambdaAligner.foldBinaryForTest(K_LONG, 8L, K_INT, 3, Opcodes.LSHR));
		assertEquals(Long.valueOf(1L), LambdaAligner.foldBinaryForTest(K_LONG, 8L, K_INT, 3, Opcodes.LUSHR));
	}

	@Test
	void plainLongArithmeticStillFolds() {
		assertEquals(Long.valueOf(9L), LambdaAligner.foldBinaryForTest(K_LONG, 4L, K_LONG, 5L, Opcodes.LADD));
	}

	@Test
	void mixedKindsAreNotFolded() {
		assertNull(LambdaAligner.foldBinaryForTest(K_LONG, 1L, K_INT, 3, Opcodes.LADD),
			"long + int 不是同类型常量运算，不应折叠");
	}
}

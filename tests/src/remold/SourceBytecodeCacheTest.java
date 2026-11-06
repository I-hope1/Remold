package remold;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourceBytecodeCacheTest {

	private static byte[] b(int v) { return new byte[]{(byte) v}; }

	@Test
	void bulkFailureThenIndividualSuccessKeepsOnlyAppliedNewSource() {
		RemoldAgent.sourceBytecodeCache.put("sc.Good", b(2));
		RemoldAgent.sourceBytecodeCache.put("sc.Bad", b(2));
		RemoldAgent.rollbackSourceCache(
			Map.of("sc.Good", b(1), "sc.Bad", b(1)),
			Set.of("sc.Good"), Set.of("sc.Good", "sc.Bad"));
		assertArrayEquals(b(2), RemoldAgent.sourceBytecodeCache.get("sc.Good"));
		assertArrayEquals(b(1), RemoldAgent.sourceBytecodeCache.get("sc.Bad"));
	}

	@Test
	void singleClassFailureRestoresThatClass() {
		RemoldAgent.sourceBytecodeCache.put("sc.One", b(2));
		RemoldAgent.sourceBytecodeCache.put("sc.Two", b(2));
		RemoldAgent.rollbackSourceCache(
			Map.of("sc.One", b(1), "sc.Two", b(1)),
			Set.of("sc.One"), Set.of("sc.One", "sc.Two"));
		assertArrayEquals(b(2), RemoldAgent.sourceBytecodeCache.get("sc.One"));
		assertArrayEquals(b(1), RemoldAgent.sourceBytecodeCache.get("sc.Two"));
	}

	@Test
	void transactionRollbackKeepsHostNewAndAnonymousOld() {
		RemoldAgent.sourceBytecodeCache.put("sc.Host", b(2));
		RemoldAgent.sourceBytecodeCache.put("sc.Host$1", b(2));
		RemoldAgent.rollbackSourceCache(
			Map.of("sc.Host", b(1), "sc.Host$1", b(1)),
			Set.of("sc.Host"), Set.of("sc.Host", "sc.Host$1"));
		assertArrayEquals(b(2), RemoldAgent.sourceBytecodeCache.get("sc.Host"));
		assertArrayEquals(b(1), RemoldAgent.sourceBytecodeCache.get("sc.Host$1"));
	}

	@Test
	void gateRejectedClassRestoresOldSource() {
		RemoldAgent.sourceBytecodeCache.put("sc.Gate", b(2));
		RemoldAgent.rollbackSourceCache(
			Map.of("sc.Gate", b(1)), Set.of(), Set.of("sc.Gate"));
		assertArrayEquals(b(1), RemoldAgent.sourceBytecodeCache.get("sc.Gate"));
	}
}

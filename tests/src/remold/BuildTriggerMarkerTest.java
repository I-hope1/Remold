package remold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildTriggerMarker} 的解析契约测试：合法内容、CRLF、未知字段、缺字段、损坏、陈旧/未来边界。
 * 全部走纯函数，不触碰文件系统。
 */
class BuildTriggerMarkerTest {

	private static final long AGENT_START = 1_700_000_000_000L;
	private static final long NOW = AGENT_START + 60_000L;
	private static final long SKEW = BuildTriggerMarker.CLOCK_SKEW_MILLIS;

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void validMarkerParsesAllFields() {
		String text = BuildTriggerMarker.format(NOW, "classes-1-abc", "classes");
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(bytes(text), NOW, AGENT_START);

		assertTrue(parsed.valid);
		assertNull(parsed.rejectReason);
		assertEquals(NOW, parsed.endMillis);
		assertEquals("classes-1-abc", parsed.buildId);
		assertEquals("classes", parsed.task);
	}

	@Test
	void crlfAndUnknownFieldsAndMissingOptionalFieldsAreAccepted() {
		String text = "remold-build-trigger v1\r\n"
		              + "build_end_millis=" + NOW + "\r\n"
		              + "task=classes\r\n"
		              + "future_field=whatever\r\n";
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(bytes(text), NOW, AGENT_START);

		assertTrue(parsed.valid);
		assertNull(parsed.buildId);
		assertEquals("classes", parsed.task);
	}

	@Test
	void missingMagicIsRejected() {
		String text = "build_end_millis=" + NOW + "\n";
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(bytes(text), NOW, AGENT_START);

		assertFalse(parsed.valid);
		assertTrue(parsed.rejectReason.contains("magic"), parsed.rejectReason);
	}

	@Test
	void missingOrNonNumericEndMillisIsRejected() {
		BuildTriggerMarker.Parsed missing = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.MAGIC + "\nbuild_id=x\n"), NOW, AGENT_START);
		assertFalse(missing.valid);
		assertTrue(missing.rejectReason.contains("build_end_millis"), missing.rejectReason);

		BuildTriggerMarker.Parsed bad = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.MAGIC + "\nbuild_end_millis=nope\n"), NOW, AGENT_START);
		assertFalse(bad.valid);
		assertTrue(bad.rejectReason.contains("not a number"), bad.rejectReason);
	}

	@Test
	void emptyAndOversizeMarkersAreRejected() {
		BuildTriggerMarker.Parsed empty = BuildTriggerMarker.parse(new byte[0], NOW, AGENT_START);
		assertFalse(empty.valid);
		assertTrue(empty.rejectReason.contains("empty"), empty.rejectReason);

		byte[] tooLarge = new byte[BuildTriggerMarker.MAX_BYTES + 1];
		Arrays.fill(tooLarge, (byte) 'x');
		BuildTriggerMarker.Parsed oversize = BuildTriggerMarker.parse(tooLarge, NOW, AGENT_START);
		assertFalse(oversize.valid);
		assertTrue(oversize.rejectReason.contains("exceeds"), oversize.rejectReason);
	}

	@Test
	void staleMarkerIsRejectedWithDelta() {
		long end = AGENT_START - SKEW - 11L;
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.format(end, "classes-old", "classes")), NOW, AGENT_START);

		assertFalse(parsed.valid);
		assertTrue(parsed.rejectReason.contains("stale"), parsed.rejectReason);
		assertEquals(-11L, parsed.deltaMillis);
	}

	@Test
	void futureMarkerIsRejectedWithDelta() {
		long end = NOW + SKEW + 7L;
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.format(end, "classes-future", "classes")), NOW, AGENT_START);

		assertFalse(parsed.valid);
		assertTrue(parsed.rejectReason.contains("future"), parsed.rejectReason);
		assertEquals(7L, parsed.deltaMillis);
	}

	@Test
	void skewBoundariesAreInclusive() {
		BuildTriggerMarker.Parsed atStaleBoundary = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.format(AGENT_START - SKEW, "b1", "classes")), NOW, AGENT_START);
		assertTrue(atStaleBoundary.valid, atStaleBoundary.rejectReason);

		BuildTriggerMarker.Parsed atFutureBoundary = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.format(NOW + SKEW, "b2", "classes")), NOW, AGENT_START);
		assertTrue(atFutureBoundary.valid, atFutureBoundary.rejectReason);
	}

	@Test
	void nonPositiveEndMillisIsRejected() {
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(
			bytes(BuildTriggerMarker.MAGIC + "\nbuild_end_millis=0\n"), NOW, AGENT_START);
		assertFalse(parsed.valid);
		assertTrue(parsed.rejectReason.contains("positive"), parsed.rejectReason);
	}
}

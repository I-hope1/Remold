package remold.ref;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HotswapBridgeWarmupTest {

	@Test
	void warmUpIsIdempotentAndLeavesStatsClean() {
		// Clear any prior test statistics before verifying warmup isolation.
		HotswapBridge.drainConditionalStats();
		HotswapBridge.drainReplayStats();

		HotswapBridge.warmUp();
		HotswapBridge.warmUp();

		Map<String, long[]> conditional = HotswapBridge.drainConditionalStats();
		Map<String, long[]> replay = HotswapBridge.drainReplayStats();

		assertTrue(conditional.isEmpty(),
			"warmup must not pollute CONDITIONAL_STATS, got: " + conditional.keySet());
		assertTrue(replay.isEmpty(),
			"warmup must not pollute REPLAY_STATS, got: " + replay.keySet());
	}
}

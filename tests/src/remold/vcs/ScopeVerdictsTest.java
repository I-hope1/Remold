package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S1/S3：空集放行必须带加载前沿时间戳；世界推进/进入保守模式/被裁剪之后，旧判决不得再被沿用。
 */
class ScopeVerdictsTest {

	private ClassLoader loader;

	@BeforeEach
	void setUp() {
		LoadFrontier.reset();
		ScopeVerdicts.INSTANCE.reset();
		loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
	}

	@AfterEach
	void tearDown() {
		LoadFrontier.reset();
		ScopeVerdicts.INSTANCE.reset();
	}

	@Test
	void neverJudgedIsUnknown() {
		assertEquals(ScopeVerdicts.Availability.UNKNOWN, ScopeVerdicts.INSTANCE.check(loader, "a/B"));
	}

	@Test
	void verdictIsFreshUntilTheFrontierAdvances() {
		LoadFrontier.birth(1L, "a/Sub", "");
		ScopeVerdicts.INSTANCE.record(loader, "a/B", true);
		assertEquals(ScopeVerdicts.Availability.FRESH, ScopeVerdicts.INSTANCE.check(loader, "a/B"));

		// 新类出生：世界推进，"没看到子类"的旧结论过期。
		LoadFrontier.birth(1L, "a/Sub2", "");
		assertEquals(ScopeVerdicts.Availability.STALE, ScopeVerdicts.INSTANCE.check(loader, "a/B"),
		 "a verdict taken before a birth must not be reused to allow the field");
		assertEquals(1L, ScopeVerdicts.INSTANCE.staleChecks());
	}

	@Test
	void conservativeWorldInvalidatesOlderVerdicts() {
		ScopeVerdicts.INSTANCE.record(loader, "a/B", true);

		// 队列触限 → conservativeOnly() 为真：更早的"安全"判决不再成立。
		LoadFrontier.reset();
		for (int i = 0; i < LoadFrontier.DEFAULT_CAPACITY + 5; i++) {
			LoadFrontier.birth(1L, "a/Noise" + i, "");
		}

		assertEquals(ScopeVerdicts.Availability.STALE, ScopeVerdicts.INSTANCE.check(loader, "a/B"),
		 "once the world is conservative-only, an earlier safety verdict must be re-judged");
	}

	@Test
	void pruningMakesAForgottenVerdictStaleNotUnknown() {
		LoadFrontier.birth(1L, "a/Sub", "");
		ScopeVerdicts.INSTANCE.record(loader, "a/B", true);

		assertEquals(1, ScopeVerdicts.INSTANCE.pruneToWindow(LoadFrontier.watermark() + 1L));
		assertEquals(ScopeVerdicts.Availability.STALE, ScopeVerdicts.INSTANCE.check(loader, "a/B"),
		 "a pruned verdict means 'we forgot', which must not read as 'never judged'");
		assertEquals(1L, ScopeVerdicts.INSTANCE.forgottenVerdicts());
	}

	@Test
	void countersSurfaceEveryTransition() {
		LoadFrontier.birth(1L, "a/Sub", "");
		ScopeVerdicts.INSTANCE.record(loader, "a/B", true);
		ScopeVerdicts.INSTANCE.check(loader, "a/B");
		ScopeVerdicts.INSTANCE.pruneToWindow(LoadFrontier.watermark() + 1L);

		assertEquals(1L, ScopeVerdicts.INSTANCE.counters().get("recorded"));
		assertEquals(1L, ScopeVerdicts.INSTANCE.counters().get("forgotten"));
	}
}

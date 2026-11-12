package remold.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code @RemoldReinit} 触发原因的公开契约（{@code docs/initfix/03-runtime-driver.md} §4）。
 *
 * <p>用户对注解的两个反向惊讶——"加了注解、只改方法体却没重置"与"改了个无关类、存量值却被重置"——
 * 只能靠日志里的触发原因区分。本用例锁住纯函数判据，防止 {@code [REINIT] trigger} 行退化成
 * 只写结论不写原因。</p>
 */
class ReinitContractTest {

	private static final String UNCHANGED = "unchanged-initializer (skipped)";

	@Test
	void newFieldAlwaysWrites() {
		assertEquals("new-field", InitFix.reinitTriggerReason(true, false, true, null, "k"));
		assertEquals("new-field", InitFix.reinitTriggerReason(true, true, false, null, null));
	}

	@Test
	void forceSwitchOverridesUnchangedFingerprint() {
		// 封闭表达式 + 指纹与上次完全相同：正常会跳过；force_reinit 打开后必须重放。
		String normal = InitFix.reinitTriggerReason(false, false, true, "k", "k");
		assertEquals(UNCHANGED, normal);
		String forced = InitFix.reinitTriggerReason(false, true, true, "k", "k");
		assertEquals("forced (remold.agent.force_reinit)", forced);
		assertNotEquals(normal, forced, "the force escape hatch must change the decision");
	}

	@Test
	void openExpressionIsNeverSkipped() {
		// 切片读到外部状态：值可能被别处改动，判不了 ⇒ 照常重置，不得走 unchanged 跳过分支。
		String reason = InitFix.reinitTriggerReason(false, false, false, "k", null);
		assertEquals("open-expression (cannot prove value unchanged)", reason);
		assertNotEquals(UNCHANGED, reason);
	}

	@Test
	void firstTimeChangedAndUnchangedAreDistinguished() {
		assertEquals("first-time", InitFix.reinitTriggerReason(false, false, true, null, "k"));
		assertEquals("initializer-changed", InitFix.reinitTriggerReason(false, false, true, "k1", "k2"));
		assertEquals(UNCHANGED, InitFix.reinitTriggerReason(false, false, true, "k2", "k2"));
	}
}

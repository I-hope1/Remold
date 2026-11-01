import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code LayoutGate} 规则表的 JUnit 入口。
 *
 * <p>这份断言不碰 {@code Instrumentation}、也不挂 agent，纯粹是 {@code LayoutGate.check}
 * 这个纯函数的规则表，所以没有理由为它单起一个 JVM。它原先由独立的 JavaExec
 * {@code layoutGateAssert} 承担，每次 {@code check} 都要冷启动一次；现在并进 {@code test}，
 * 进程开销直接归零。独立任务仍保留，方便只跑这一张表时用。</p>
 *
 * <p>明细打在 stdout 里（{@link LayoutGateAssert#run()} 的 PASS/FAIL 行），这里只断言总数。</p>
 */
public class LayoutGateRulesTest {

	@Test
	void rulesTableHolds() {
		assertEquals(0, LayoutGateAssert.run(), "LayoutGate 规则表断言（明细见 stdout）");
	}
}

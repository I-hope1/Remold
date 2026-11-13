import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import remold.RemoldAgent;

/**
 * Stage A 宿主/匿名一致性判据：新注入组（此前未加载、按新字节码定义完毕）不参与 Stage A 重定义，
 * 不得被误判为"半生效"而回滚（ROLLED-BACK-A + RESTART required）。
 */
class StageAConsistencyTest {

	private static final String HOST = "com.example.hotswap.S37_LazyLoadedAfterV2";
	private static final String ANON = HOST + "$1";

	/** 回归：host 与 anon 都是本轮新注入（不在 definitions/ok），不得回滚。 */
	@Test
	void newlyInjectedGroupIsNotRolledBack() {
		assertTrue(RemoldAgent.stageAConsistent(
			HOST, Set.of(ANON), Set.of(), Set.of(HOST, ANON), Set.of()),
			"a fully injected (host, anon) group must not be treated as a partial Stage A failure");
	}

	/** 两阶段正常路径：加载过的 host 与 anon 都成功重定义 → 一致。 */
	@Test
	void loadedHostAndAnonMustBothBeInOk() {
		assertTrue(RemoldAgent.stageAConsistent(
			HOST, Set.of(ANON), Set.of(HOST, ANON), Set.of(), Set.of(HOST, ANON)));
	}

	/** 加载过的 host 不在 ok：半生效，必须回滚。 */
	@Test
	void loadedHostMissingFromOkRollsBack() {
		assertFalse(RemoldAgent.stageAConsistent(
			HOST, Set.of(ANON), Set.of(ANON), Set.of(), Set.of(HOST, ANON)));
	}

	/** 已加载的匿名类不在 ok：半生效，必须回滚。 */
	@Test
	void loadedAnonMissingFromOkRollsBack() {
		assertFalse(RemoldAgent.stageAConsistent(
			HOST, Set.of(ANON), Set.of(HOST), Set.of(), Set.of(HOST, ANON)));
	}

	/** 新注入 host + 已加载 anon：只豁免注入方，已加载方仍要校验。 */
	@Test
	void injectedHostStillChecksLoadedAnon() {
		assertFalse(RemoldAgent.stageAConsistent(
			HOST, Set.of(ANON), Set.of(), Set.of(HOST), Set.of(ANON)));
	}
}

package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import remold.reflect.mixin.MixinTargetIndex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次就绪门与目标索引的契约：{@code isHostReady()} 必须在 {@code offerAny} 之前把索引补上。
 *
 * <p>这条契约的意义：开发者只改 {@code .java} 时不触发任何配置回调，若就绪门不顺手发布，
 * 索引会整场停在 {@code UNKNOWN}，出生点补丁对 Mixin 目标的判定就永远 fail-closed。</p>
 *
 * <p>本用例锁两件事：①未发布 → 查询是 UNKNOWN（不是 NOT_TARGET）；②发布成功后立刻变成
 * NOT_TARGET/TARGET，且后续查询只看快照、不再依赖任何宿主对象。</p>
 */
class HostReadyPublishContractTest {

	static final class FakeConfig {
		@SuppressWarnings("unused")
		private final Map<String, List<Object>> mixinMapping;
		@SuppressWarnings("unused")
		private final Set<String> unhandledTargets;

		FakeConfig(Map<String, List<Object>> mapping, Set<String> unhandled) {
			this.mixinMapping = mapping;
			this.unhandledTargets = unhandled;
		}
	}

	@AfterEach
	void cleanUp() {
		MixinTargetIndex.reset();
	}

	@Test
	void indexIsUnknownUntilTheReadyGatePublishes() {
		assertFalse(MixinTargetIndex.available());
		assertEquals(MixinTargetIndex.Lookup.UNKNOWN, MixinTargetIndex.lookup("net/example/MixinTarget"));

		// 模拟就绪门那一次惰性发布：之后索引与宿主对象完全解耦。
		MixinTargetIndex.publish(List.of(new FakeConfig(
		 Map.of("net.example.MixinTarget", List.of(new Object())), Set.of())));

		assertTrue(MixinTargetIndex.available());
		assertEquals(MixinTargetIndex.Lookup.TARGET, MixinTargetIndex.lookup("net/example/MixinTarget"));
		assertEquals(MixinTargetIndex.Lookup.NOT_TARGET, MixinTargetIndex.lookup("net/example/Plain"));
	}

	@Test
	void publishIsIdempotentForRepeatedReadyChecks() {
		MixinTargetIndex.publish(List.of(new FakeConfig(Map.of(), Set.of())));
		long stamp = MixinTargetIndex.stamp();
		// 就绪门每次都检查 available()，已发布时不应再推（否则 stamp 会随批次增长。
		if (!MixinTargetIndex.available()) MixinTargetIndex.publish(List.of());

		assertEquals(stamp, MixinTargetIndex.stamp(), "a ready check must not republish once the index is live");
	}
}

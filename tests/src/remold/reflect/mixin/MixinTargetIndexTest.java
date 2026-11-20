package remold.reflect.mixin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixin 目标索引的三态语义（{@code docs/vcs/07} §5 护栏 2 的数据源）。
 *
 * <p>夹具用普通对象模拟宿主配置：{@code mixinMapping} / {@code unhandledTargets} 两个字段由反射读取，
 * 因此这里验证的是真实的读取路径，不是打桩分支。</p>
 */
class MixinTargetIndexTest {

	/** 模拟宿主 MixinConfig：只有被反射读取的两个字段。 */
	static final class FakeConfig {
		@SuppressWarnings("unused")
		private final Map<String, List<Object>> mixinMapping;
		@SuppressWarnings("unused")
		private final Set<String> unhandledTargets;

		FakeConfig(Map<String, List<Object>> mixinMapping, Set<String> unhandledTargets) {
			this.mixinMapping = mixinMapping;
			this.unhandledTargets = unhandledTargets;
		}
	}

	@AfterEach
	void cleanUp() {
		MixinTargetIndex.reset();
	}

	/** 没有发布过配置时必须是 UNKNOWN —— 不能当成"不是目标"。 */
	@Test
	void unpublishedIndexIsUnknownNotNotTarget() {
		assertFalse(MixinTargetIndex.available());
		assertEquals(MixinTargetIndex.Lookup.UNKNOWN, MixinTargetIndex.lookup("a/b/C"));
	}

	/** 映射键是点分名，查询给斜杠名：必须命中（两侧都归一化）。 */
	@Test
	void dottedMappingKeyMatchesSlashLookup() {
		MixinTargetIndex.publish(List.of(new FakeConfig(
		 Map.of("net.example.Target", List.of(new Object())), Set.of())));

		assertEquals(MixinTargetIndex.Lookup.TARGET, MixinTargetIndex.lookup("net/example/Target"));
		assertEquals(MixinTargetIndex.Lookup.NOT_TARGET, MixinTargetIndex.lookup("net/example/Other"));
	}

	/** 未解析目标（{@code @Mixin(targets=...)} 字符串形态）同样算目标。 */
	@Test
	void unhandledTargetsCountAsTargets() {
		MixinTargetIndex.publish(List.of(new FakeConfig(Map.of(), Set.of("net.example.StringTarget"))));

		assertEquals(MixinTargetIndex.Lookup.TARGET, MixinTargetIndex.lookup("net/example/StringTarget"));
	}

	/** 整表替换：第二次 publish 后，旧目标不得残留（否则补丁会被幽灵目标长期拒绝）。 */
	@Test
	void publishReplacesInsteadOfMerging() {
		MixinTargetIndex.publish(List.of(new FakeConfig(Map.of("a.Old", List.of()), Set.of())));
		assertEquals(MixinTargetIndex.Lookup.TARGET, MixinTargetIndex.lookup("a/Old"));

		MixinTargetIndex.publish(List.of(new FakeConfig(Map.of("a.New", List.of()), Set.of())));
		assertEquals(MixinTargetIndex.Lookup.NOT_TARGET, MixinTargetIndex.lookup("a/Old"),
		 "a target removed from the configs must not stay TARGET forever");
		assertEquals(MixinTargetIndex.Lookup.TARGET, MixinTargetIndex.lookup("a/New"));
		assertEquals(2L, MixinTargetIndex.stamp(), "each publish bumps the stamp");
	}

	/** 空配置列表：索引可用但没有目标，查询是 NOT_TARGET 而不是 UNKNOWN。 */
	@Test
	void publishedButEmptyIsNotTarget() {
		MixinTargetIndex.publish(List.of(new FakeConfig(Map.of(), Set.of())));

		assertTrue(MixinTargetIndex.available());
		assertEquals(MixinTargetIndex.Lookup.NOT_TARGET, MixinTargetIndex.lookup("a/b/C"));
	}

	/**
	 * 热刷新期间不得出现"半空表瞬间"：持续发布时，一个在两份配置里都存在的目标
	 * 一次都不能被读成 NOT_TARGET（那是会冲掉上游织入的错误方向）。
	 */
	@Test
	void concurrentPublishNeverExposesAnEmptySnapshot() throws Exception {
		FakeConfig first = new FakeConfig(Map.of("a.Stable", List.of(), "a.Old", List.of()), Set.of());
		FakeConfig second = new FakeConfig(Map.of("a.Stable", List.of(), "a.New", List.of()), Set.of());
		MixinTargetIndex.publish(List.of(first));

		java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger wrong = new java.util.concurrent.atomic.AtomicInteger();
		Thread reader = new Thread(() -> {
			while (!stop.get()) {
				if (MixinTargetIndex.lookup("a/Stable") == MixinTargetIndex.Lookup.NOT_TARGET) wrong.incrementAndGet();
			}
		});
		reader.start();
		for (int i = 0; i < 2000; i++) {
			MixinTargetIndex.publish(List.of(i % 2 == 0 ? first : second));
		}
		stop.set(true);
		reader.join(2000);

		assertEquals(0, wrong.get(),
		 "publish must swap an immutable snapshot, never clear-then-fill under a concurrent reader");
	}
}

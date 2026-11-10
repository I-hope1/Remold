package remold.reflect.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MixinClassInfoRegistry} 的契约测试：已注册 Mixin 的 {@code ClassInfo.cache} 条目必须被锚回
 * {@code MixinInfo.getClassInfo()}。
 *
 * <p>背景：上游 {@code ClassInfo.forName} 在缓存未命中时会用普通 {@code ClassNode} 构造
 * {@code ClassInfo}（{@code isMixin == false}），随后
 * {@code MixinTargetContext.transformSingleDescriptor} 会跳过 Mixin→Target 的描述符重映射，匿名内部类
 * {@code this$0} 的构造器签名停留在 Mixin 类上（运行期 {@code NoSuchMethodError}）。</p>
 *
 * <p>真实 {@code ClassInfo} 的静态初始化依赖 MixinService，普通 JUnit 里不可用，因此这里通过
 * 包内可见的重载注入一张普通 {@code Map} 作为缓存表；反射层（字段/方法名）仍由真实 Mixin jar 上的
 * 契约测试（{@code MixinReloadPolicyTest.mixinInternalsStillMatchTheReflectionContract}）守着。</p>
 */
class MixinClassInfoRegistryTest {

	private static final String MIXIN = "com.example.mixin.ProbeMixin";
	private static final String MIXIN_INTERNAL = "com/example/mixin/ProbeMixin";

	@Test
	void registeredMixinClassInfoIsAnchoredBackIntoTheCache() {
		Map<Object, Object> cache = new HashMap<>();
		FakeClassInfo stale = new FakeClassInfo(false);
		cache.put(MIXIN_INTERNAL, stale);
		FakeClassInfo canonical = new FakeClassInfo(true);
		FakeTransformer transformer = transformer(new FakeInfo(MIXIN, canonical));

		int corrected = MixinClassInfoRegistry.reassert(transformer, cache);

		assertEquals(1, corrected, "exactly one registered mixin entry must be re-anchored");
		assertSame(canonical, cache.get(MIXIN_INTERNAL),
			"ClassInfo.cache must hold MixinInfo.getClassInfo() by identity (isMixin=true)");
		assertEquals(0, MixinClassInfoRegistry.reassert(transformer, cache),
			"a second pass must be a no-op (idempotent)");
	}

	@Test
	void missingCacheEntryIsAdded() {
		Map<Object, Object> cache = new HashMap<>();
		FakeClassInfo canonical = new FakeClassInfo(true);

		int corrected = MixinClassInfoRegistry.reassert(transformer(new FakeInfo(MIXIN, canonical)), cache);

		assertEquals(1, corrected);
		assertSame(canonical, cache.get(MIXIN_INTERNAL));
	}

	@Test
	void entriesWithoutMixinInfoAreNotInvented() {
		Map<Object, Object> cache = new HashMap<>();

		int corrected = MixinClassInfoRegistry.reassert(transformer(new FakeInfo(MIXIN, null)), cache);

		assertEquals(0, corrected);
		assertFalse(cache.containsKey(MIXIN_INTERNAL), "no ClassInfo may be written when MixinInfo has none");
	}

	@Test
	void emptyRegistryIsANoOp() {
		Map<Object, Object> cache = new HashMap<>();
		assertEquals(0, MixinClassInfoRegistry.reassert(new FakeTransformer(), cache));
		assertEquals(0, MixinClassInfoRegistry.reassert(null, cache));
		assertTrue(cache.isEmpty());
	}

	// ------------------------------------------------------------------ fixtures

	private static FakeTransformer transformer(FakeInfo info) {
		FakeConfig config = new FakeConfig();
		config.mixins.add(info);
		FakeTransformer transformer = new FakeTransformer();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));
		return transformer;
	}

	private static final class FakeTransformer {
		Object processor;
	}

	private static final class FakeProcessor {
		@SuppressWarnings("unused")
		private final List<FakeConfig> configs;

		FakeProcessor(List<FakeConfig> configs) {
			this.configs = configs;
		}
	}

	private static final class FakeConfig {
		final List<FakeInfo> mixins = new ArrayList<>();
	}

	private static final class FakeInfo {
		private final String className;
		private final FakeClassInfo classInfo;

		FakeInfo(String className, FakeClassInfo classInfo) {
			this.className = className;
			this.classInfo = classInfo;
		}

		String getClassName() {
			return this.className;
		}

		FakeClassInfo getClassInfo() {
			return this.classInfo;
		}
	}

	private static final class FakeClassInfo {
		@SuppressWarnings("unused")
		private final boolean isMixin;

		FakeClassInfo(boolean isMixin) {
			this.isMixin = isMixin;
		}
	}
}

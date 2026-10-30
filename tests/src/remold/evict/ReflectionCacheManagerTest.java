package remold.evict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ReflectionCacheManagerTest {

	static class SampleTarget1 {}
	static class SampleTarget2 {}
	static class UnrelatedTarget {}

	// 模拟 Arc Json 内部结构
	static class MockArcJson {
		final Map<Class<?>, Object> typeToFields = new HashMap<>();
		final Map<Class<?>, Object[]> classToDefaultValues = new HashMap<>();
		final Map<Class<?>, Object> classToSerializer = new HashMap<>();
	}

	// 模拟 Gson 内部结构
	static class MockGson {
		final Map<Object, Object> typeTokenCache = new HashMap<>();
	}

	@Test
	@DisplayName("SPI 驱逐器注册与全生命周期因果验证")
	public void testCustomEvictorLifecycle() {
		AtomicInteger evictCallCount = new AtomicInteger();
		Set<Class<?>> receivedClasses = new HashSet<>();

		ReflectionCacheEvictor customEvictor = new ReflectionCacheEvictor() {
			@Override
			public String getName() { return "CustomTestEvictor"; }

			@Override
			public int evict(Set<Class<?>> redefinedClasses) {
				evictCallCount.incrementAndGet();
				receivedClasses.addAll(redefinedClasses);
				return redefinedClasses.size();
			}
		};

		// 1. 注册并执行
		ReflectionCacheManager.registerEvictor(customEvictor);
		Set<Class<?>> targets = Set.of(SampleTarget1.class, SampleTarget2.class);

		int count = ReflectionCacheManager.evictAll(targets);
		assertTrue(count >= 2, "Evicted count must include at least custom evictor count");
		assertEquals(1, evictCallCount.get(), "Custom evictor must be invoked exactly once");
		assertTrue(receivedClasses.contains(SampleTarget1.class), "Target1 must be passed to evictor");
		assertTrue(receivedClasses.contains(SampleTarget2.class), "Target2 must be passed to evictor");

		// 2. 注销后不再触发
		ReflectionCacheManager.unregisterEvictor(customEvictor);
		ReflectionCacheManager.evictAll(Set.of(SampleTarget1.class));
		assertEquals(1, evictCallCount.get(), "Unregistered evictor must not be invoked again");
	}

	@Test
	@DisplayName("Arc Json 实例级缓存驱逐因果性与对照组")
	public void testArcJsonInstanceEviction() {
		MockArcJson mockJson = new MockArcJson();
		mockJson.typeToFields.put(SampleTarget1.class, "meta1");
		mockJson.typeToFields.put(UnrelatedTarget.class, "unrelatedMeta");
		mockJson.classToDefaultValues.put(SampleTarget1.class, new Object[]{"def"});
		mockJson.classToSerializer.put(SampleTarget1.class, "ser");

		// 前置基线断言
		assertTrue(mockJson.typeToFields.containsKey(SampleTarget1.class), "Baseline: Target1 must be in cache");
		assertTrue(mockJson.typeToFields.containsKey(UnrelatedTarget.class), "Baseline: Unrelated must be in cache");

		// 注册实例并触发重定义驱逐
		ReflectionCacheManager.registerArcJson(mockJson);
		int evicted = ReflectionCacheManager.evictAll(Set.of(SampleTarget1.class));
		assertTrue(evicted >= 1, "Must report evicted count for Target1");

		// 因果终局断言：Target1 被精准清出，但 UnrelatedTarget 依然完好
		assertFalse(mockJson.typeToFields.containsKey(SampleTarget1.class),
			"Causal End-State: Target1 must be evicted from typeToFields");
		assertFalse(mockJson.classToDefaultValues.containsKey(SampleTarget1.class),
			"Causal End-State: Target1 must be evicted from classToDefaultValues");
		assertFalse(mockJson.classToSerializer.containsKey(SampleTarget1.class),
			"Causal End-State: Target1 must be evicted from classToSerializer");

		// 对照组：无关类未受任何影响
		assertTrue(mockJson.typeToFields.containsKey(UnrelatedTarget.class),
			"Counterfactual Control: UnrelatedTarget must remain in cache");
	}

	@Test
	@DisplayName("Gson 实例级 typeTokenCache 清除验证")
	public void testGsonInstanceEviction() {
		MockGson mockGson = new MockGson();
		mockGson.typeTokenCache.put("Token1", "Adapter1");
		mockGson.typeTokenCache.put("Token2", "Adapter2");

		// 前置基线断言
		assertEquals(2, mockGson.typeTokenCache.size(), "Baseline: Cache must have 2 entries");

		ReflectionCacheManager.registerGson(mockGson);
		ReflectionCacheManager.evictAll(Set.of(SampleTarget1.class));

		// 因果终局断言
		assertEquals(0, mockGson.typeTokenCache.size(), "Causal End-State: typeTokenCache must be cleared");
	}

	@Test
	@DisplayName("异常隔离性：单个 Evictor 异常不影响其它 Evictor 执行")
	public void testEvictorExceptionIsolation() {
		AtomicInteger survivorInvoked = new AtomicInteger();

		ReflectionCacheEvictor faulty = new ReflectionCacheEvictor() {
			@Override
			public String getName() { return "FaultyEvictor"; }
			@Override
			public int evict(Set<Class<?>> redefinedClasses) {
				throw new RuntimeException("Simulated evictor failure");
			}
		};

		ReflectionCacheEvictor survivor = new ReflectionCacheEvictor() {
			@Override
			public String getName() { return "SurvivorEvictor"; }
			@Override
			public int evict(Set<Class<?>> redefinedClasses) {
				survivorInvoked.incrementAndGet();
				return 1;
			}
		};

		ReflectionCacheManager.registerEvictor(faulty);
		ReflectionCacheManager.registerEvictor(survivor);

		try {
			// 断言不会抛出未捕获异常
			assertDoesNotThrow(() -> ReflectionCacheManager.evictAll(Set.of(SampleTarget1.class)));
			assertEquals(1, survivorInvoked.get(), "Survivor evictor must be invoked despite faulty evictor failure");
		} finally {
			ReflectionCacheManager.unregisterEvictor(faulty);
			ReflectionCacheManager.unregisterEvictor(survivor);
		}
	}
}

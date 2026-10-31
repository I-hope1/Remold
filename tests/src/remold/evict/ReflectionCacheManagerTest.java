package remold.evict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.arc.ArcJsonEvictor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ReflectionCacheManagerTest {

	static class SampleTarget1 { int value; }
	static class SampleTarget2 {}
	static class UnrelatedTarget { int other; }

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
	@DisplayName("Arc Json 实例级缓存驱逐因果性与对照组（真实 Arc 类型）")
	public void testArcJsonInstanceEviction() {
		arc.util.serialization.Json json = new arc.util.serialization.Json();

		// 前置基线：字段表已进入缓存，且二次读取命中同一实例（证明缓存确实生效）
		arc.struct.OrderedMap<String, arc.util.serialization.Json.FieldMetadata> baseline =
			json.getFields(SampleTarget1.class);
		assertSame(baseline, json.getFields(SampleTarget1.class),
			"Baseline: second getFields must hit the cached map");

		// 对照组基线：无关类同样进入缓存
		arc.struct.OrderedMap<String, arc.util.serialization.Json.FieldMetadata> control =
			json.getFields(UnrelatedTarget.class);

		// 注册实例与驱逐器，触发重定义驱逐
		ArcJsonEvictor.register(json);
		ReflectionCacheManager.registerEvictor(ArcJsonEvictor.INSTANCE);
		try {
			ReflectionCacheManager.evictAll(Set.of(SampleTarget1.class));
		} finally {
			ReflectionCacheManager.unregisterEvictor(ArcJsonEvictor.INSTANCE);
			ArcJsonEvictor.unregister(json);
		}

		// 因果终局断言：Target1 的缓存条目被丢弃，重新反射出新的字段表
		assertNotSame(baseline, json.getFields(SampleTarget1.class),
			"Causal End-State: Target1 field cache must be re-derived, not the cached instance");

		// 对照组：无关类缓存原样保留
		assertSame(control, json.getFields(UnrelatedTarget.class),
			"Counterfactual Control: UnrelatedTarget cache must be untouched");

		// 计数语义：getFields 只填充 typeToFields，故重新填充后恰好命中 1 条
		json.getFields(SampleTarget1.class);
		assertEquals(1, ArcJsonEvictor.INSTANCE.evict(Set.of(SampleTarget1.class)),
			"Evictor must report exactly the entries it removed from typeToFields");
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

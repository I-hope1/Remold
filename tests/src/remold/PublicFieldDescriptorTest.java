package remold;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开字段的<b>声明类型</b>是二进制兼容契约（旧插件 jar 按字段描述符在链接期解析它们）。
 *
 * <p><b>为什么必须有这条守卫</b>：{@code plugin-fabric} / {@code plugin-mc-toast} 是<b>独立发布</b>的
 * 制品，已发布的版本（{@code v0.2.7} / {@code v0.2.8} / {@code v0.3.0-load-frontier}）里
 * {@code HostReadinessProbe} / {@code McToastPresenter} 以
 * {@code getstatic RemoldAgent.loadedClassesMap : Ljava/util/concurrent/ConcurrentHashMap;} 的形态访问它。
 * 字段声明类型一改（哪怕运行时放进去的是更好的实现），旧插件就在<b>链接期</b>抛
 * {@code NoSuchFieldError}；{@code RemoldAgentJarCheck} 只检查 agent jar 自己，看不到这种错配。</p>
 *
 * <p>因此契约是：<b>声明类型 = 旧描述符</b>（{@code Map} / {@code ConcurrentHashMap}），
 * <b>运行期实例 = 新的实现类</b>（能力升级、描述符不变）。本测试把两半都钉住：只钉前者会放过
 * "退回旧实现"的退化，只钉后者会放过"描述符漂移"。</p>
 */
class PublicFieldDescriptorTest {

	/** {@code loadedClassesMap}：旧描述符是 {@code ConcurrentHashMap}，运行期是 {@link LoadedClassIndex}。 */
	@Test
	void loadedClassesMapKeepsTheConcurrentHashMapDescriptor() throws Exception {
		Field field = RemoldAgent.class.getDeclaredField("loadedClassesMap");
		assertEquals(ConcurrentHashMap.class, field.getType(),
		 "the declared type is the bytecode descriptor old plugin jars link against"
				+ " — changing it is a NoSuchFieldError at link time");
		assertTrue(field.getType().isAssignableFrom(LoadedClassIndex.class),
		 "the new implementation must be assignable to the declared type (no wrapper, no mirror map)");
		assertInstanceOf(LoadedClassIndex.class, RemoldAgent.loadedClassesMap,
		 "the runtime instance must be the scoped index, not a plain map");
	}

	/** {@code pendingAlignedClasses}：旧描述符是 {@code Map}，运行期是实现了 {@code Map} 的新实现。 */
	@Test
	void pendingAlignedClassesKeepsTheMapDescriptor() throws Exception {
		Field field = AnnotationTransformer.class.getDeclaredField("pendingAlignedClasses");
		assertEquals(Map.class, field.getType(),
		 "the declared type must stay java.util.Map (the descriptor old plugin jars link against)");
		assertNotNull(AnnotationTransformer.pendingAlignedClasses, "the view must be wired to an instance");
		assertInstanceOf(Map.class, AnnotationTransformer.pendingAlignedClasses,
		 "the object behind the Map-typed field must itself be a Map (old binaries call Map methods on it)");
		assertInstanceOf(AnnotationTransformer.PendingAlignedClasses.class,
		 AnnotationTransformer.pendingAlignedClasses,
		 "and it must be the scoped implementation, not a downgraded plain map");
	}

	/** {@code inventedSlots}：旧描述符是 {@code Map}，按 scope 分键的实现仍在 {@code Map} 面之后。 */
	@Test
	void inventedSlotsKeepsTheMapDescriptor() throws Exception {
		Field field = RemoldAgent.class.getDeclaredField("inventedSlots");
		assertEquals(Map.class, field.getType(),
		 "the declared type must stay java.util.Map (the descriptor old plugin jars link against)");
		assertInstanceOf(ScopeKeyedNames.class, RemoldAgent.inventedSlots,
		 "the runtime instance must be the scope-keyed implementation");
		assertEquals(RemoldAgent.INVENTED_SLOTS, RemoldAgent.inventedSlots,
		 "the Map view and the scope-aware handle must be the same instance (no two copies of the state)");
	}
}

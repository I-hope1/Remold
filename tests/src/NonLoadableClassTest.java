package remold;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixin 模板类只能作为织入模板存在，不能被 Remold 当普通新类 define，也不能进入读者扫描。
 *
 * <p>覆盖两个出口：{@code loadClassSnap} 排除、{@code injectNewClass} 跳过。
 * 后者用已加载的 fixture 名 + 无效字节：如果守卫被移除，defineClass 会抛 LinkageError，
 * 旧实现会把它当“已加载”返回 true，测试即变红。</p>
 */
public class NonLoadableClassTest {

	@Test
	void markedTemplateIsExcludedFromLoadedSnapshot() throws Exception {
		String name = NonLoadableFixture.class.getName();
		RemoldAgent.markNonLoadableClass(name);
		assertTrue(RemoldAgent.isNonLoadableClass(name));

		ConcurrentHashMap<String, Class<?>> previous = RemoldAgent.loadedClassesMap;
		try {
			Method load = RemoldAgent.class.getDeclaredMethod("loadClassSnap", Class[].class);
			load.setAccessible(true);
			load.invoke(null, (Object) new Class<?>[] {NonLoadableFixture.class});
			assertFalse(RemoldAgent.loadedClassesMap.containsKey(name),
				"a non-loadable template must not enter loadedClassesMap");
		} finally {
			RemoldAgent.loadedClassesMap = previous;
		}
	}

	@Test
	void markedTemplateIsNeverDefinedAsANewClass() throws Exception {
		String name = NonLoadableFixture.class.getName();
		RemoldAgent.markNonLoadableClass(name);

		ConcurrentHashMap<String, Class<?>> previous = RemoldAgent.loadedClassesMap;
		try {
			// Make findTargetClassLoader succeed: the loader lookup walks loadedClassesMap by package.
			RemoldAgent.loadedClassesMap.put("remold.NonLoadableSibling", NonLoadableFixture.class);
			Method inject = RemoldAgent.class.getDeclaredMethod(
				"injectNewClass", String.class, Path.class, byte[].class);
			inject.setAccessible(true);
			Object result = inject.invoke(null, name, null, new byte[] {0, 0, 0, 0});

			assertEquals(Boolean.FALSE, result,
				"injectNewClass must short-circuit before defineClass for a non-loadable template");
		} finally {
			RemoldAgent.loadedClassesMap = previous;
		}
	}
}

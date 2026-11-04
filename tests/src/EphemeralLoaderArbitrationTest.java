import org.junit.jupiter.api.Test;
import remold.RemoldAgent;
import remold.offset.LayoutPredictor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同名类多副本仲裁的回归断言。
 *
 * <p>背景（真机故障）：{@code LayoutPredictor} 用抛弃式加载器独立加载新字节码预测物理布局，
 * 该加载器以真实加载器为 parent 且 child-first，于是它定义的副本在类层级上"更子"。
 * {@code loadClassSnap} 恰好按"最子层优先"选类，于是预测副本会盖掉活类：
 * 之后每一轮热更都在 redefine 一个从不初始化、也没有任何实例的死副本。
 * 表现是日志照样打 {@code HotSwap successful}，但 InitFix 因"类未初始化"跳过补丁、
 * {@code @OnReload} 也扫不到——用户看到的就是"加了字段却不生效"。</p>
 *
 * <p>本类不跳过任何用例（{@code verifyTestCount} 要求 {@code skipped == 0}）。</p>
 */
class EphemeralLoaderArbitrationTest {

	private static final String SELF = EphemeralLoaderArbitrationTest.class.getName();
	private static final String SELF_INTERNAL = SELF.replace('.', '/');

	@Test
	void predictionCopyMustNotShadowTheLiveClass() throws Exception {
		Class<?> live = EphemeralLoaderArbitrationTest.class;
		ClassLoader ephemeral = new LayoutPredictor.EphemeralClassLoader(
			Map.of(SELF_INTERNAL, classBytes()), null, live.getClassLoader());
		Class<?> dead = ephemeral.loadClass(SELF);

		assertNotSame(live, dead, "fixture must really create a second Class object");
		assertEquals(SELF, dead.getName(), "both copies must share the same binary name");
		assertTrue(LayoutPredictor.isEphemeralLoader(ephemeral), "ephemeral loader must be registered");

		assertFalse(RemoldAgent.isBetterLoadedClassCandidate(dead, live),
			"a throwaway prediction copy must never take over the live class");
		assertTrue(RemoldAgent.isBetterLoadedClassCandidate(live, dead),
			"the live class must displace a prediction copy already held by the map");
	}

	@Test
	void genuineChildLoaderStillWins() throws Exception {
		Class<?> live = EphemeralLoaderArbitrationTest.class;
		ClassLoader child = new ChildFirst(live.getClassLoader(), classBytes());
		Class<?> childCopy = child.loadClass(SELF);

		assertNotSame(live, childCopy, "fixture must really create a second Class object");
		assertFalse(LayoutPredictor.isEphemeralLoader(child), "control loader must not be marked ephemeral");
		assertTrue(RemoldAgent.isBetterLoadedClassCandidate(childCopy, live),
			"a real nested loader must still take precedence (Fabric/Knot rely on it)");
	}

	private static byte[] classBytes() throws IOException {
		try (InputStream in = EphemeralLoaderArbitrationTest.class
			.getResourceAsStream("EphemeralLoaderArbitrationTest.class")) {
			assertNotNull(in, "class file must be on the test classpath");
			return in.readAllBytes();
		}
	}

	/** 对照用的普通 child-first 加载器：不是预测加载器，必须保持原有的"更子优先"。 */
	private static final class ChildFirst extends ClassLoader {
		private final byte[] bytes;

		ChildFirst(ClassLoader parent, byte[] bytes) {
			super(parent);
			this.bytes = bytes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!SELF.equals(name)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if (c == null) c = defineClass(name, bytes, 0, bytes.length);
				if (resolve) resolveClass(c);
				return c;
			}
		}
	}
}

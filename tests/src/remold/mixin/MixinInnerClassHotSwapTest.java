package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

/**
 * Mixin 匿名/内部类热更的判定力测试。
 *
 * <p>覆盖三件事，都是 R3 / R5 闭环上真正会静默出错的地方：</p>
 * <ol>
 *   <li>哪些内部类属于某个 Mixin（决定要给谁登记字节、要对谁做合成类重定义）；</li>
 *   <li>{@code Outer$1} → {@code Outer} 的归属推导（决定只改匿名类时会不会回到外层 Mixin）；</li>
 *   <li>「只改方法体」与「改了成员结构」的区分（决定能不能用 JVM redefine 落地，而不是绕过布局门）。</li>
 * </ol>
 */
class MixinInnerClassHotSwapTest {

	// ------------------------------------------------------------ 无实质变更短路

	@Test
	void syntheticRedefineIsSkippedWhenBytesAreIdentical() {
		String name = "fixture.Target$Anonymous$deadbeef";
		ClassLoader loader = new ClassLoader() { };
		byte[] loaded = new byte[] {1, 2, 3, 4};
		byte[] previous = remold.RemoldAgent.bytecodeCache.put(name, loaded);
		try {
			assertTrue(MixinInnerClassHotSwap.isUnchanged(loader, name, loaded.clone()),
				"byte-identical regeneration must be short-circuited (no redundant redefine)");
			assertFalse(MixinInnerClassHotSwap.isUnchanged(loader, name, new byte[] {1, 2, 3, 5}),
				"a single changed byte must still be redefined");
		} finally {
			restoreCache(name, previous);
		}
	}

	@Test
	void unknownSyntheticClassIsNeverShortCircuited() {
		String name = "fixture.Target$Anonymous$unknown";
		assertFalse(MixinInnerClassHotSwap.isUnchanged(new ClassLoader() { }, name, new byte[] {9}),
			"without a known loaded snapshot the redefine must proceed (fail-open to redefinition)");
	}

	private static void restoreCache(String name, byte[] previous) {
		if (previous == null) {
			remold.RemoldAgent.bytecodeCache.remove(name);
		} else {
			remold.RemoldAgent.bytecodeCache.put(name, previous);
		}
	}

	// ------------------------------------------------------------ 内部类归属

	@Test
	void collectsOnlyTheMixinsOwnInnerClasses() {
		ClassNode node = new ClassNode();
		node.name = "com/example/mixin/FooMixin";
		node.innerClasses = Arrays.asList(
			// 自己的匿名类
			new InnerClassNode("com/example/mixin/FooMixin$1", "com/example/mixin/FooMixin", null, 0),
			// 自己的成员内部类
			new InnerClassNode("com/example/mixin/FooMixin$Inner", "com/example/mixin/FooMixin", "Inner", Opcodes.ACC_PUBLIC),
			// 自己的 static + synthetic 内部类：上游当作 passthrough，不生成唯一副本
			new InnerClassNode("com/example/mixin/FooMixin$LambdaHolder",
				"com/example/mixin/FooMixin", "LambdaHolder", Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC),
			// 别人的内部类
			new InnerClassNode("com/example/mixin/BarMixin$1", "com/example/mixin/BarMixin", null, 0),
			// Mixin 自己（不应算作自己的内部类）
			new InnerClassNode("com/example/mixin/FooMixin", null, "FooMixin", Opcodes.ACC_PUBLIC));

		List<String> own = MixinInnerClassSupport.ownInnerClassNames("com/example/mixin/FooMixin", node);

		assertEquals(Arrays.asList("com/example/mixin/FooMixin$1", "com/example/mixin/FooMixin$Inner"), own,
			"only the mixin's own, non-passthrough inner classes may be registered/regenerated");
	}

	@Test
	void derivesOuterFromNestedName() {
		assertTrue(MixinInnerClassSupport.isNestedClassName("com/example/FooMixin$1"));
		assertFalse(MixinInnerClassSupport.isNestedClassName("com/example/FooMixin"));
		assertEquals("com/example/FooMixin", MixinInnerClassSupport.outerInternalName("com/example/FooMixin$1"),
			"an anonymous class must route back to the enclosing mixin");
		assertEquals("com/example/FooMixin$1",
			MixinInnerClassSupport.outerInternalName("com/example/FooMixin$1$2"),
			"nested anonymous classes must peel exactly one level");
		assertNull(MixinInnerClassSupport.outerInternalName("com/example/FooMixin"));
	}

	// ------------------------------------------------------------ 结构契约

	@Test
	void bodyOnlyChangeIsAllowed() throws Exception {
		Class<?> live = Sample.class;
		ClassNode fresh = nodeOf(live);

		assertNull(MixinInnerClassHotSwap.describeSchemaChange(live, fresh),
			"a body-only change keeps the member schema, so redefine is allowed");
	}

	@Test
	void addedFieldIsRejectedWithDirectedReason() throws Exception {
		Class<?> live = Sample.class;
		ClassNode fresh = nodeOf(live);
		fresh.fields.add(new org.objectweb.asm.tree.FieldNode(
			Opcodes.ACC_PRIVATE, "remold$added", "I", null, null));

		String reason = MixinInnerClassHotSwap.describeSchemaChange(live, fresh);

		assertNotNull(reason, "adding a field to an already-loaded anonymous class is a structural change");
		assertTrue(reason.contains("fields added") && reason.contains("remold$added"),
			"the diagnostic must name the exact member that blocks the redefine: " + reason);
		assertTrue(reason.contains("restart"),
			"the diagnostic must say what the developer has to do: " + reason);
	}

	@Test
	void addedMethodIsRejectedWithDirectedReason() throws Exception {
		Class<?> live = Sample.class;
		ClassNode fresh = nodeOf(live);
		org.objectweb.asm.tree.MethodNode added =
			new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "remold$extra", "()V", null, null);
		added.visitCode();
		added.visitInsn(Opcodes.RETURN);
		added.visitMaxs(0, 1);
		added.visitEnd();
		fresh.methods.add(added);

		String reason = MixinInnerClassHotSwap.describeSchemaChange(live, fresh);

		assertNotNull(reason, "adding a method to an already-loaded anonymous class is a structural change");
		assertTrue(reason.contains("methods added") && reason.contains("remold$extra"),
			"the diagnostic must name the exact member that blocks the redefine: " + reason);
	}

	// ------------------------------------------------------------ 字节登记表

	@Test
	void overlayRegistryRoundTripAndRelease() {
		String dot = "com/example/mixin/FooMixin$1";
		byte[] bytes = new byte[]{1, 2, 3};

		MixinBytecodeOverlay.register(dot, bytes);
		assertEquals(bytes, MixinBytecodeOverlay.lookup(dot),
			"a registered inner class must be resolvable by internal name");
		assertEquals(bytes, MixinBytecodeOverlay.lookup("com.example.mixin.FooMixin$1"),
			"dotted and internal forms must resolve to the same entry");
		assertNull(MixinBytecodeOverlay.lookup("com/example/mixin/OtherMixin$1"),
			"an unrelated class must not be shadowed by the overlay");

		MixinBytecodeOverlay.clear(Collections.singletonList(dot));
		assertNull(MixinBytecodeOverlay.lookup(dot), "a rejected reload must release the registered bytes");
	}

	@Test
	void evictsNegativeClassInfoEntries() {
		// 复刻上游 ClassInfo.forName 的失败形态：读不到类时把 null 写进缓存且永不重试。
		// 用真实 Map + 真实的 null 值，判定点就是「按 containsKey 计数、按 remove 落地」。
		Map<String, Object> cache = new java.util.HashMap<>();
		String internal = "remold/test/NegativeCacheProbe$1";
		cache.put(internal, null);
		cache.put("remold/test/Unrelated", null);
		assertTrue(cache.containsKey(internal), "precondition: the negative entry is present");

		int removed = MixinBytecodeOverlay.evictFromCache(cache, Collections.singletonList(internal));

		assertEquals(1, removed, "a null-valued (negative) entry must be counted as evicted");
		assertFalse(cache.containsKey(internal),
			"the negative entry must be gone, otherwise reload keeps reading stale/null metadata");
		assertTrue(cache.containsKey("remold/test/Unrelated"),
			"eviction must be scoped to the requested names");
	}

	@Test
	void evictsDottedNamesToo() {
		Map<String, Object> cache = new java.util.HashMap<>();
		cache.put("com/example/mixin/FooMixin$1", null);
		assertEquals(1, MixinBytecodeOverlay.evictFromCache(cache,
			Collections.singletonList("com.example.mixin.FooMixin$1")),
			"dotted and internal forms must address the same cache entry");
	}

	// ------------------------------------------------------------ helpers

	private static ClassNode nodeOf(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			assertNotNull(in, "test fixture bytecode must be on the test runtime classpath");
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, 0);
			return node;
		}
	}

	/** 结构判定的真实载体：字段与方法集合固定，只有方法体可变。 */
	static final class Sample {
		int value;

		String describe() {
			return "v1:" + this.value;
		}

		@Override
		public String toString() {
			return describe();
		}
	}
}

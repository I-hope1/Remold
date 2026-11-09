package remold.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import remold.BytecodeSources;
import remold.RemoldAgent;

/**
 * 织入期生成的合成内部类字节「回灌」通道的判定力测试。
 *
 * <p>真实形态是 Mixin 的 {@code InnerClassGenerator} 在织入期按
 * {@code Target$Anonymous$<uuid>} 生成合成内部类、并把它登记进目标类的 {@code nestMembers}。
 * 这类字节磁盘上没有 {@code .class}，也不经过 Remold 的 {@code ClassFileTransformer}，所以
 * {@link HierarchyTreeOracle} 的两条原有来源（{@code bytecodeCache} 与 ClassLoader 资源流）
 * 都读不到它，导致：</p>
 * <ul>
 *   <li>{@code getNestMembers} 抛 {@code nest member metadata unavailable}，被 {@code InitFix}
 *       捕成 {@code complete=false}（Nest 零写入证明降级 fail-closed）；</li>
 *   <li>同一 {@code resolve} 链上的构造器可见性判定返回"无法确定"，字段初始化 patch 被拒
 *       （{@code cannot determine protected status of constructor ...}）。</li>
 * </ul>
 *
 * <p>用例逐条锁住 {@link BytecodeSources} 登记口的两个必要性质：<b>登记前后判定必须翻转</b>
 * （去掉回灌接线即变红），以及<b>登记口不遮蔽 classpath 上的普通类</b>（换无关输入对照）。</p>
 */
class NestBytecodeSourcesTest {

	private static final String HOST     = "remold/ref/probe/NestReplayHost";
	private static final String SYNTHETIC = HOST + "$Anonymous$0123456789abcdef";
	private static final String SELF      = "remold/ref/NestBytecodeSourcesTest";

	// ------------------------------------------------------------ Nest 扫描

	/**
	 * 前置基线：合成类既不在 classpath 上也不在 {@code bytecodeCache} 里时，Nest 扫描必须失败。
	 *
	 * <p>这条同时是余下正例的"原本即为空"证明——不先确认失败，后面"登记后成功"就可能是
	 * 别的来源碰巧读到了字节（假阳性）。</p>
	 */
	@Test
	void unregisteredSyntheticNestMemberFailsClosed() {
		ClassNode host = classNode(hostBytes(HOST, SYNTHETIC));

		assertNull(RemoldAgent.bytecodeCache.get(SYNTHETIC.replace('/', '.')),
			"precondition: the synthetic class must not be in Remold's bytecode cache");
		assertFalse(classpathHas(SYNTHETIC),
			"precondition: the synthetic class must not exist as a .class resource");

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> new HierarchyTreeOracle(null, host).getNestMembers(HOST),
			"an unresolvable nest member must fail the scan (fail-closed), never be silently skipped");
		assertTrue(failure.getMessage().contains("nest member metadata unavailable"),
			"the failure must be attributable to the missing member: " + failure.getMessage());
		assertTrue(failure.getMessage().contains(SYNTHETIC),
			"the failure must name the member that could not be read: " + failure.getMessage());
	}

	/**
	 * 登记后同一调用必须成功，并且真的把该成员的字节解析出来（不只是占位）。
	 */
	@Test
	void registeredSyntheticNestMemberCompletesNestScan() {
		ClassNode host = classNode(hostBytes(HOST, SYNTHETIC));
		Function<String, byte[]> source = sourceOf(SYNTHETIC, syntheticBytes(SYNTHETIC, HOST, HOST, Opcodes.ACC_PUBLIC));
		BytecodeSources.register(source);
		try {
			List<ClassNode> members = new HierarchyTreeOracle(null, host).getNestMembers(HOST);

			ClassNode found = null;
			for (ClassNode member : members) {
				if (SYNTHETIC.equals(member.name)) found = member;
			}
			assertNotNull(found,
				"the registered synthetic class must show up as a nest member, got: " + names(members));
			assertTrue(hasMethod(found, "poke"),
				"the member must be parsed from the registered bytes, not stubbed");

			List<ClassHierarchyOracle.NestFieldWrite> writes =
				new HierarchyTreeOracle(null, host).getNestFieldWrites(HOST);
			boolean sawSyntheticWrite = false;
			for (ClassHierarchyOracle.NestFieldWrite write : writes) {
				if (SYNTHETIC.equals(write.className) && "poke".equals(write.methodName)) {
					sawSyntheticWrite = true;
				}
			}
			assertTrue(sawSyntheticWrite,
				"the registered bytes must be scanned for nest writes; otherwise the zero-write proof"
					+ " silently loses this member. writes=" + describe(writes));
		} finally {
			BytecodeSources.unregister(source);
		}
	}

	/** 撤销登记后必须回到 fail-closed：登记口不留"读到过一次就永远算已知"的残留。 */
	@Test
	void unregisterRestoresFailClosed() {
		ClassNode host = classNode(hostBytes(HOST, SYNTHETIC));
		Function<String, byte[]> source = sourceOf(SYNTHETIC, syntheticBytes(SYNTHETIC, HOST, HOST, Opcodes.ACC_PUBLIC));

		BytecodeSources.register(source);
		assertNotNull(new HierarchyTreeOracle(null, host).getNestMembers(HOST),
			"precondition: registration actually made the nest resolvable");

		assertTrue(BytecodeSources.unregister(source), "unregister must report the removal");
		assertThrows(IllegalStateException.class,
			() -> new HierarchyTreeOracle(null, host).getNestMembers(HOST),
			"after unregistering, the scan must fail again instead of using stale bytes");
	}

	// ------------------------------------------------------------ 构造器可见性

	/**
	 * {@code InitFix} 的构造器可见性判定的三态（镜像
	 * {@code isProtectedCrossPackageAccess} 与调用方的拒绝文案）。
	 */
	private enum Visibility {
		/** {@code resolveMember} 取不到成员 → 调用方拒绝（fail-closed）。 */
		UNDETERMINABLE,
		/** 可判定且不是"跨包 protected" → 放行。 */
		ALLOWED,
		/** 可判定且是跨包 protected → 拒绝（该原因与"无法确定"不同）。 */
		PROTECTED_CROSS_PACKAGE
	}

	/**
	 * 登记前：构造器可见性必须判不出来（fail-closed）。
	 *
	 * <p>这就是真机 {@code poke=s7=null} 的判定侧形态——字段初始化 patch 因为没有元数据被拒。</p>
	 */
	@Test
	void constructorVisibilityIsUndeterminableWithoutBytes() {
		ClassNode host = classNode(hostBytes(HOST));
		String desc = "(L" + HOST + ";)V";

		assertFalse(classpathHas(SYNTHETIC),
			"precondition: the anonymous class has no .class on the classpath");

		assertEquals(Visibility.UNDETERMINABLE,
			ctorVisibility(new HierarchyTreeOracle(null, host), HOST, SYNTHETIC, desc),
			"without the synthetic bytes the constructor's access flags cannot be read, so the"
				+ " caller must stay fail-closed");
	}

	/** 登记后：同一判定从"无法确定"翻转为"可判定 → 放行"。 */
	@Test
	void constructorVisibilityIsAllowedOnceBytesAreRegistered() {
		ClassNode host = classNode(hostBytes(HOST));
		String desc = "(L" + HOST + ";)V";
		Function<String, byte[]> source = sourceOf(SYNTHETIC,
			syntheticBytes(SYNTHETIC, HOST, HOST, Opcodes.ACC_PROTECTED));
		BytecodeSources.register(source);
		try {
			assertEquals(Visibility.ALLOWED,
				ctorVisibility(new HierarchyTreeOracle(null, host), HOST, SYNTHETIC, desc),
				"a protected constructor declared in the same package is not a cross-package access,"
					+ " so registration must let the field-init patch through");
		} finally {
			BytecodeSources.unregister(source);
		}
	}

	/**
	 * 登记后读到的是<b>真实</b> access flags，不是"有字节就当放行"：
	 * 跨包的 protected 构造器必须判成 {@link Visibility#PROTECTED_CROSS_PACKAGE}。
	 */
	@Test
	void protectedCrossPackageConstructorIsDeterminedNotDefaulted() {
		String foreign = "remold/ref/probe/other/WeavedAnonymous$Anonymous$fedcba9876543210";
		String desc = "(L" + HOST + ";)V";

		assertEquals(Visibility.UNDETERMINABLE,
			ctorVisibility(new HierarchyTreeOracle(null, null), HOST, foreign, desc),
			"precondition: the foreign class is unknown before registration");

		Function<String, byte[]> source = sourceOf(foreign,
			syntheticBytes(foreign, null, HOST, Opcodes.ACC_PROTECTED));
		BytecodeSources.register(source);
		try {
			assertEquals(Visibility.PROTECTED_CROSS_PACKAGE,
				ctorVisibility(new HierarchyTreeOracle(null, null), HOST, foreign, desc),
				"the access flags of the registered bytes must drive the decision, so a"
					+ " cross-package protected constructor stays refused for its own reason");
		} finally {
			BytecodeSources.unregister(source);
		}
	}

	// ------------------------------------------------------------ 登记口语义

	/** 内部名与点分名都能命中（登记方用哪种键法都能被查到）。 */
	@Test
	void lookupAcceptsInternalAndDottedKeys() {
		String internal = "remold/ref/probe/Keyed$Anonymous$aaa";
		String dotted   = "remold.ref.probe.Keyed$Anonymous$aaa";
		byte[] bytes    = new byte[]{1, 2, 3};

		Function<String, byte[]> byInternal = key -> internal.equals(key) ? bytes : null;
		BytecodeSources.register(byInternal);
		try {
			assertSame(bytes, BytecodeSources.lookup(internal), "internal form must resolve");
			assertSame(bytes, BytecodeSources.lookup(dotted), "dotted form must resolve the same entry");
			assertNull(BytecodeSources.lookup("remold/ref/probe/Other$Anonymous$bbb"),
				"an unrelated class must not be invented by the port");
		} finally {
			BytecodeSources.unregister(byInternal);
		}

		Function<String, byte[]> byDotted = key -> dotted.equals(key) ? bytes : null;
		BytecodeSources.register(byDotted);
		try {
			assertSame(bytes, BytecodeSources.lookup(internal),
				"a source keyed by dotted name must still answer internal-name queries");
		} finally {
			BytecodeSources.unregister(byDotted);
		}
	}

	/** 登记顺序即查询顺序：先登记者优先，返回 null 的来源只填空；抛异常的来源被跳过且不打断调用。 */
	@Test
	void lookupKeepsRegistrationOrderAndSurvivesBrokenSources() {
		String name = "remold/ref/probe/Order$Anonymous$ccc";
		byte[] first = new byte[]{1};
		byte[] second = new byte[]{2};

		Function<String, byte[]> throwing = key -> {
			throw new IllegalStateException("broken source");
		};
		Function<String, byte[]> empty = key -> null;
		Function<String, byte[]> winner = sourceOf(name, first);
		Function<String, byte[]> loser = sourceOf(name, second);

		BytecodeSources.register(throwing);
		BytecodeSources.register(empty);
		BytecodeSources.register(winner);
		BytecodeSources.register(loser);
		try {
			assertSame(first, BytecodeSources.lookup(name),
				"the earlier registration wins; a throwing source is skipped without breaking the caller");
		} finally {
			BytecodeSources.unregister(throwing);
			BytecodeSources.unregister(empty);
			BytecodeSources.unregister(winner);
			BytecodeSources.unregister(loser);
		}
		assertNull(BytecodeSources.lookup(name), "all sources must be gone after unregistering");
	}

	/**
	 * 换无关输入对照：登记合成类字节不得遮蔽 classpath 上的普通类。
	 */
	@Test
	void registeredSourceDoesNotShadowClasspathClasses() {
		Function<String, byte[]> source = sourceOf(SYNTHETIC, syntheticBytes(SYNTHETIC, HOST, HOST, Opcodes.ACC_PUBLIC));
		BytecodeSources.register(source);
		try {
			assertNull(BytecodeSources.lookup(SELF),
				"the port must not fabricate bytes for classes it was never given");
			HierarchyTreeOracle oracle = new HierarchyTreeOracle(null, null);
			assertTrue(oracle.resolveMember(SELF, "classNode",
					"([B)Lorg/objectweb/asm/tree/ClassNode;", false).isPresent(),
				"classpath classes must keep resolving through the resource-stream fallback");
			assertTrue(oracle.resolveMember(SELF, "<init>", "()V", false).isPresent(),
				"member resolution for classpath classes must be unaffected by registration");
		} finally {
			BytecodeSources.unregister(source);
		}
	}

	// ------------------------------------------------------------ plugin 接线

	/**
	 * 端到端接线：plugin-mixin 的「合成类回灌」入口登记后，core 的 Nest 扫描必须能完成。
	 *
	 * <p>生产路径是 {@code MixinInnerClassHotSwap.apply} 在 {@code generate(...)} 成功后调用这个入口；
	 * 这里用反射直接调用同一方法（该类包私有且依赖 Mixin 运行期对象，无法在无 Mixin 的测试进程里
	 * 走完整 apply），把 plugin → core 这一段真实接起来。少了这次回灌，core 侧在真机依旧
	 * {@code nest member metadata unavailable}。</p>
	 */
	@Test
	void mixinPluginRefeedFeedsCoreNestScan() throws Exception {
		String host      = "remold/ref/probe/PluginRefeedHost";
		String synthetic = host + "$Anonymous$abcdef0123456789";
		ClassNode hostNode = classNode(hostBytes(host, synthetic));
		byte[] bytes = syntheticBytes(synthetic, host, host, Opcodes.ACC_PROTECTED);

		assertFalse(classpathHas(synthetic),
			"precondition: the synthetic class exists only in memory (no .class on disk)");
		assertThrows(IllegalStateException.class,
			() -> new HierarchyTreeOracle(null, hostNode).getNestMembers(host),
			"precondition: core cannot resolve the synthetic class before the plugin re-feeds it");

		java.lang.reflect.Method register = Class.forName("remold.mixin.MixinInnerClassHotSwap")
			.getDeclaredMethod("registerGenerated", String.class, byte[].class);
		register.setAccessible(true);
		register.invoke(null, synthetic.replace('/', '.'), bytes);

		boolean found = false;
		for (ClassNode member : new HierarchyTreeOracle(null, hostNode).getNestMembers(host)) {
			if (synthetic.equals(member.name)) found = true;
		}
		assertTrue(found,
			"the plugin's re-fed bytes must make the synthetic member resolvable to core");
		assertEquals(Visibility.ALLOWED,
			ctorVisibility(new HierarchyTreeOracle(null, hostNode), host, synthetic, "(L" + host + ";)V"),
			"the same re-fed bytes must also make the constructor visibility decidable");
	}

	// ------------------------------------------------------------ helpers

	/** 镜像 {@code InitFix.isProtectedCrossPackageAccess} 与调用方的三态结果。 */
	private static Visibility ctorVisibility(ClassHierarchyOracle oracle, String hostInternal,
	                                         String ownerInternal, String desc) {
		Optional<ClassHierarchyOracle.MemberRef> member = oracle.resolveMember(ownerInternal, "<init>", desc, false);
		if (member.isEmpty()) return Visibility.UNDETERMINABLE;
		boolean prot = Modifier.isProtected(member.get().access)
			&& !packageOf(member.get().declaringClass).equals(packageOf(hostInternal));
		return prot ? Visibility.PROTECTED_CROSS_PACKAGE : Visibility.ALLOWED;
	}

	private static String packageOf(String internalName) {
		int separator = internalName.lastIndexOf('/');
		return separator < 0 ? "" : internalName.substring(0, separator);
	}

	private static Function<String, byte[]> sourceOf(String className, byte[] bytes) {
		Map<String, byte[]> map = new HashMap<>();
		map.put(className, bytes);
		return map::get;
	}

	private static ClassNode classNode(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static boolean hasMethod(ClassNode node, String name) {
		if (node.methods == null) return false;
		for (org.objectweb.asm.tree.MethodNode method : node.methods) {
			if (name.equals(method.name)) return true;
		}
		return false;
	}

	private static String names(List<ClassNode> nodes) {
		StringBuilder sb = new StringBuilder();
		for (ClassNode node : nodes) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(node.name);
		}
		return sb.toString();
	}

	private static String describe(List<ClassHierarchyOracle.NestFieldWrite> writes) {
		StringBuilder sb = new StringBuilder();
		for (ClassHierarchyOracle.NestFieldWrite write : writes) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(write.className).append('.').append(write.methodName);
		}
		return sb.toString();
	}

	/** 该名字在 classpath 上有没有 {@code .class}（探测完即关闭资源）。 */
	private static boolean classpathHas(String internalName) {
		ClassLoader loader = ClassLoader.getSystemClassLoader();
		if (loader == null) return false;
		try (java.io.InputStream in = loader.getResourceAsStream(internalName + ".class")) {
			return in != null;
		} catch (java.io.IOException failure) {
			throw new IllegalStateException(failure);
		}
	}

	/** 目标类字节：只声明 nestMembers，成员清单由合成类自己声明 nestHost。 */
	private static byte[] hostBytes(String host, String... nestMembers) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, host, null, "java/lang/Object", null);
		for (String nestMember : nestMembers) {
			writer.visitNestMember(nestMember);
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * 织入期生成的合成内部类字节：{@code <init>} 的 access 由参数决定，
	 * 另有一个写自身字段的方法（供 Nest 写指令扫描验证）。
	 *
	 * @param nestHost       非 {@code null} 时声明 nestHost（同一个 Nest 内的真实形态）
	 * @param ctorParamOwner 构造器唯一形参的类型（内部名）
	 */
	private static byte[] syntheticBytes(String internalName, String nestHost, String ctorParamOwner,
	                                     int ctorAccess) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V11, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null,
			"java/lang/Object", null);
		if (nestHost != null) {
			writer.visitNestHost(nestHost);
		}
		String ctorDesc = "(L" + ctorParamOwner + ";)V";
		MethodVisitor ctor = writer.visitMethod(ctorAccess, "<init>", ctorDesc, null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 2);
		ctor.visitEnd();

		writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();
		MethodVisitor poke = writer.visitMethod(Opcodes.ACC_PRIVATE, "poke", "(I)V", null, null);
		poke.visitCode();
		poke.visitVarInsn(Opcodes.ALOAD, 0);
		poke.visitVarInsn(Opcodes.ILOAD, 1);
		poke.visitFieldInsn(Opcodes.PUTFIELD, internalName, "value", "I");
		poke.visitInsn(Opcodes.RETURN);
		poke.visitMaxs(2, 2);
		poke.visitEnd();

		writer.visitEnd();
		return writer.toByteArray();
	}
}

package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URI;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.nio.file.Files;
import java.nio.file.Path;

import remold.ref.PendingLoadPatch;
import remold.reflect.mixin.MixinTargetIndex;
import remold.vcs.AlignmentMetaStore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出生点补丁（{@code docs/vcs/07-load-frontier-and-birth-watermark.md} §5）。
 *
 * <p>红基线 B（{@code BirthStaleBytesRedBaselineTest}）已整体反转：当年断言"出生点不替换、缓存记旧字节"，
 * 现在断言"有生效字节就替换、并且替换后的字节才是被记账的生效字节"。</p>
 */
class BirthPatchTest {

	private static final String INTERNAL = "probe/PatchTarget";
	private static final String DOT = "probe.PatchTarget";

	private Path projectDir;
	private ClassLoader loader;

	@AfterEach
	void cleanUp() {
		PendingLoadPatch.reset();
		MixinTargetIndex.reset();
		RemoldAgent.bytecodeCache.remove(DOT);
		if (projectDir != null) RemoldAgent.activeWatchDirs.remove(projectDir);
	}

	private ProtectionDomain projectDomain() throws Exception {
		projectDir = Files.createTempDirectory("remold-birth-patch");
		RemoldAgent.activeWatchDirs.add(projectDir);
		CodeSource cs = new CodeSource(URI.create(projectDir.toUri().toString()).toURL(), (Certificate[]) null);
		loader = new java.net.URLClassLoader(new java.net.URL[] {projectDir.toUri().toURL()},
		 getClass().getClassLoader());
		return new ProtectionDomain(cs, null);
	}

	private static byte[] classBytes(String marker) {
		return classBytes(marker, "java/lang/Object");
	}

	private static byte[] classBytes(String marker, String superName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INTERNAL, null, superName, null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "marker",
		 "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(marker);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** 工作区内的类：有待决补丁时，出生必须拿到 v2 字节而不是加载器送来的 v1。 */
	@Test
	void projectBirthIsPatchedToTheNewBytes() {
		byte[] v1 = classBytes("V1");
		byte[] v2 = classBytes("V2");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, INTERNAL, v1, true,
		 (dot, raw) -> raw);

		assertTrue(d.applied(), "a pending patch must be applied to the birth");
		assertArrayEquals(v2, d.bytes(), "the birth must serve the new bytes, not the stale buffer");
		assertEquals(0, PendingLoadPatch.pending(), "the pending entry is consumed exactly once");
		assertEquals(1L, PendingLoadPatch.appliedCount());
	}

	/** 换无关输入：没有待决记录时不干预（对照）。 */
	@Test
	void unrelatedClassIsUntouched() {
		byte[] v1 = classBytes("V1");
		PendingLoadPatch.Decision d = PendingLoadPatch.decide(99L, "probe/Other", v1, true,
		 (dot, raw) -> raw);
		assertEquals(PendingLoadPatch.Outcome.NONE, d.outcome());
		assertNull(d.bytes(), "no pending patch means no substitution at all");
	}

	/** 非工作区类：不替换（护栏 1 的归属校验）。 */
	@Test
	void nonProjectClassIsRefused() {
		byte[] v1 = classBytes("V1");
		byte[] v2 = classBytes("V2");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, INTERNAL, v1, false,
		 (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.REFUSED_NON_PROJECT, d.outcome());
		assertNull(d.bytes(), "third-party classes must never be rewritten by this path");
		assertEquals(1L, PendingLoadPatch.refusedCount());
	}

	/** 护栏 2：buffer 已含 Mixin 生成成员时 fail-closed，保留上游织入结果。 */
	@Test
	void wovenBufferIsRefused() {
		byte[] v2 = classBytes("V2");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, v2);

		byte[] woven = classBytesWithMixinHandler();
		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, INTERNAL, woven, true,
		 (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.REFUSED_WOVEN, d.outcome(),
		 "a buffer that already carries Mixin-generated members must be kept as-is");
		assertNull(d.bytes());
		assertEquals(1L, PendingLoadPatch.refusedCount());
	}

	/** 护栏 2 的另一半：合成类形态直接拒绝，即使工作区里有同名产物。 */
	@Test
	void syntheticNameIsRefused() {
		byte[] v2 = classBytes("V2");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, "probe/Target$Anonymous$abc", v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, "probe/Target$Anonymous$abc",
		 classBytes("V1"), true, (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.REFUSED_WOVEN, d.outcome());
	}

	/** 护栏 1 的身份维度：loader scope 不匹配时不得替换（防跨 loader 冒名）。 */
	@Test
	void crossLoaderImpersonationIsRefused() {
		byte[] v2 = classBytes("V2");
		long ownerScope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(ownerScope, INTERNAL, v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(ownerScope + 1L, INTERNAL,
		 classBytes("V1"), true, (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.NONE, d.outcome(),
		 "a different loader must not consume another loader's pending patch");
		assertEquals(1, PendingLoadPatch.pending(), "the owner's entry is still there");
	}

	/** 护栏 3：载荷必须经过 pre-Mixin 变换，而不是直接用磁盘 Raw。 */
	@Test
	void payloadComesFromSourceTransformsNotRawDisk() {
		byte[] raw = classBytes("RAW");
		byte[] transformed = classBytes("TRANSFORMED");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, raw);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, INTERNAL, classBytes("V1"), true,
		 (dot, bytes) -> {
			 assertEquals(DOT, dot, "the transform must be asked for the dot name");
			 return transformed;
		 });

		assertTrue(d.applied());
		assertArrayEquals(transformed, d.bytes(),
		 "the payload must be the transform output, never the raw disk bytes");
	}

	/** 等价短路：载荷与当前 buffer 相同时不替换，但待决记录要消费掉。 */
	@Test
	void identicalPayloadShortCircuits() {
		byte[] same = classBytes("SAME");
		long scope = remold.vcs.AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, same);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(scope, INTERNAL, same, true,
		 (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.EQUAL, d.outcome());
		assertNull(d.bytes());
		assertEquals(0, PendingLoadPatch.pending(), "the entry must still be consumed");
	}

	private static byte[] classBytesWithMixinHandler() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INTERNAL, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
		 "handler$zce000$cfg$foo", "(I)V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 穿过真实转换器链的集成断言：AnnotationTransformer 决定生效字节，CacheWarmingTransformer 记账。
	 *
	 * <p>这三条正是"注释掉接线是否必挂"的自检点：替换、单次出生记账、继承树拿到生效字节。</p>
	 */
	@Test
	void transformerChainAppliesPatchRecordsOneBirthAndRegistersEffectiveBytes() throws Exception {
		ProtectionDomain pd = projectDomain();
		byte[] v1 = classBytes("V1", "probe/V1Base");
		byte[] v2 = classBytes("V2", "probe/V2Base");
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, v2);

		remold.vcs.LoadFrontier.reset();
		byte[] fromAnnotation = new remold.AnnotationTransformer()
		 .transform(loader, INTERNAL, null, pd, v1);
		assertArrayEquals(v2, fromAnnotation,
		 "the annotation transformer must return the patched bytes to the JVM");

		// JVM 会把返回的字节喂给下一个 transformer（注册顺序：AnnotationTransformer 在前）。
		byte[] effective = fromAnnotation == null ? v1 : fromAnnotation;
		byte[] fromCacheWarming = new RemoldAgent.CacheWarmingTransformer()
		 .transform(loader, INTERNAL, null, pd, effective);
		assertNull(fromCacheWarming, "the cache-warming transformer never substitutes bytes");
		assertArrayEquals(v2, RemoldAgent.getRuntimeBytecode(DOT),
		 "the recorded runtime baseline must be the effective (patched) bytes");

		assertEquals(1L, remold.vcs.LoadFrontier.watermark(),
		 "one load must advance the frontier exactly once (no double accounting)");

		assertTrue(remold.AnnotationTransformer.HierarchyTree
		 .knownSubtypes("probe/V2Base", loader).contains(INTERNAL),
		 "the hierarchy tree must register the effective bytes (v2 superclass)");
		assertFalse(remold.AnnotationTransformer.HierarchyTree
		 .knownSubtypes("probe/V1Base", loader).contains(INTERNAL),
		 "the stale superclass must not be the one recorded");
	}

	/** 对齐队列优先：对齐字节与出生补丁同时存在时用对齐字节，并把补丁消费掉。 */
	@Test
	void alignmentQueueWinsOverBirthPatch() throws Exception {
		ProtectionDomain pd = projectDomain();
		byte[] v1 = classBytes("V1", "java/lang/Object");
		byte[] aligned = classBytes("ALIGNED", "probe/AlignedBase");
		byte[] patch = classBytes("PATCH", "probe/PatchBase");
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		PendingLoadPatch.offer(scope, INTERNAL, patch);
		remold.AnnotationTransformer.pendingAlignedClasses.put(INTERNAL, aligned);

		byte[] result = new remold.AnnotationTransformer()
		 .transform(loader, INTERNAL, null, pd, v1);

		assertArrayEquals(aligned, result,
		 "already-computed alignment bytes must not be overwritten by the raw birth patch");
		assertEquals(0, PendingLoadPatch.pending(),
		 "the pending patch must be discarded once the alignment queue supplied effective bytes");
	}

	/** 批次里"类尚未加载、加载器未知"的登记：出生时无论谁成为定义加载器都能拿到这次编辑。 */
	@Test
	void unknownScopeOfferIsConsumedByWhicheverLoaderLoadsFirst() {
		byte[] v2 = classBytes("V2");
		PendingLoadPatch.offerAny(INTERNAL, v2);

		// 加载器此刻才确定：任意 scope 都应命中这条 ANY_SCOPE 记录。
		PendingLoadPatch.Decision d = PendingLoadPatch.decide(424242L, INTERNAL, classBytes("V1"), true,
		 (dot, raw) -> raw);

		assertTrue(d.applied(), "the pending patch registered before the loader was known must still apply");
		assertArrayEquals(v2, d.bytes());
		assertEquals(0, PendingLoadPatch.pending(), "consumed once");
	}

	/** 但归属校验仍然优先：外部类即使名字相同也不能消费这条记录。 */
	@Test
	void unknownScopeOfferStillRequiresProjectOwnership() {
		byte[] v2 = classBytes("V2");
		PendingLoadPatch.offerAny(INTERNAL, v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(7L, INTERNAL, classBytes("V1"), false,
		 (dot, raw) -> raw);

		assertFalse(d.applied());
		assertEquals(PendingLoadPatch.Outcome.REFUSED_NON_PROJECT, d.outcome());
	}

	/**
	 * 生产路径的名字形态：批次侧登记的是<b>点分名</b>（{@code Utils.getClassNameASM} 产出），
	 * 出生点是<b>斜杠内部名</b>。两侧必须归一化，否则会静默全量 miss。
	 *
	 * <p>这条用例走真实链路：用点分名登记 → 经 {@code AnnotationTransformer} 以斜杠名出生。</p>
	 */
	@Test
	void dottedOfferIsConsumedBySlashInternalNameAtBirth() throws Exception {
		ProtectionDomain pd = projectDomain();
		byte[] v1 = classBytes("V1", "probe/V1Base");
		byte[] v2 = classBytes("V2", "probe/V2Base");
		// 批次侧真实形态：点分名。
		PendingLoadPatch.offerAny(DOT, v2);
		assertEquals(1, PendingLoadPatch.pending());

		byte[] result = transformAtBirth(pd, v1);

		assertArrayEquals(v2, result,
		 "a dotted registration must still be found by the slash internal name at birth");
		assertEquals(0, PendingLoadPatch.pending(), "the dotted entry must be consumed");
		assertEquals(1L, PendingLoadPatch.appliedCount());
	}

	/** 同上，但登记侧给斜杠名、出生侧给点分名：两个方向都要通。 */
	@Test
	void slashOfferIsConsumedByDottedLookup() {
		byte[] v1 = classBytes("V1");
		byte[] v2 = classBytes("V2");
		PendingLoadPatch.offerAny(INTERNAL, v2);

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(5L, DOT, v1, true, (dot, raw) -> raw);

		assertTrue(d.applied(), "normalization must work in both directions");
		assertArrayEquals(v2, d.bytes());
	}

	private byte[] transformAtBirth(ProtectionDomain pd, byte[] buffer) throws Exception {
		return new remold.AnnotationTransformer().transform(loader, INTERNAL, null, pd, buffer);
	}

	/** 确认是 Mixin 目标时拒绝出生点补丁：上游织入结果必须保留。 */
	@Test
	void mixinTargetIsRefused() {
		byte[] v2 = classBytes("V2");
		PendingLoadPatch.offerAny(INTERNAL, v2);
		MixinTargetIndex.publish(java.util.List.of(new Object() {
			@SuppressWarnings("unused")
			private final java.util.Map<String, java.util.List<Object>> mixinMapping =
			 java.util.Map.of(DOT, java.util.List.of(new Object()));
			@SuppressWarnings("unused")
			private final java.util.Set<String> unhandledTargets = java.util.Set.of();
		}));

		PendingLoadPatch.Decision d = PendingLoadPatch.decide(1L, INTERNAL, classBytes("V1"), true,
		 (dot, raw) -> raw);

		assertEquals(PendingLoadPatch.Outcome.REFUSED_MIXIN_TARGET, d.outcome(),
		 "a confirmed mixin target must never be overwritten by the birth patch");
		MixinTargetIndex.reset();
	}
}

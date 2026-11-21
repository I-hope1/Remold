package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import remold.vcs.AlignmentMetaStore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pending 映射的 ClassLoader 隔离（{@code docs/status.md} §5 风险 15）。
 *
 * <p>隔离级别与 {@code AlignmentMetaStore} / {@code LoadFrontier} / {@code InitFix} 一致：scope 号
 * 只由 {@link AlignmentMetaStore#loaderScopeId} 分配，键 = {@code scope + '\0' + 类名}；
 * "加载器未知"用 {@code PendingLoadPatch.ANY_SCOPE} 哨兵，出生时先精确后哨兵。</p>
 *
 * <p>每条用例都断言两件事：跨 loader 拿不到（隔离），同 loader 行为不变（兼容）。只断言前者会
 * 放过"一律不命中"的假修复。</p>
 */
class PendingLoaderIsolationTest {

	private static final String HOST_INTERNAL = "probe/IsoAlign$1";
	private static final String HOST_DOT = "probe.IsoAlign$1";
	private static final String INV = "probe.InventedTarget";

	private final List<FixtureLoader> loaders = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		AnnotationTransformer.pendingAlignedClasses.clear();
		RemoldAgent.pendingInvented.clear();
		RemoldAgent.loadedClassesMap.remove(INV);
		RemoldAgent.bytecodeCache.remove(HOST_DOT);
		RemoldAgent.bytecodeCache.remove(INV);
		for (FixtureLoader loader : loaders) RemoldAgent.activeWatchDirs.remove(loader.projectDir);
		loaders.clear();
	}

	/**
	 * {loader 身份, 类名} 分键：A 名下的对齐字节不得被 B 的首次加载取走。
	 *
	 * <p>红基线形态就是原实现的纯类名键：B 的 {@code transform} 会直接命中 A 的条目并把它消费掉。</p>
	 */
	@Test
	void pendingAlignedBytesOfOneLoaderAreNotVisibleToAnother() throws Exception {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		long scopeA = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA);
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);
		assertTrue(scopeA != scopeB, "precondition: two loaders must map to two distinct scopes");

		byte[] aligned = classBytes(HOST_INTERNAL, true);
		AnnotationTransformer.pendingAlignedClasses.put(scopeA, HOST_INTERNAL, aligned);
		AnnotationTransformer.pendingAlignedClasses.put(scopeA, HOST_DOT, aligned);

		byte[] forB = new AnnotationTransformer().transform(loaderB, HOST_INTERNAL, null, loaderB.domain, classBytes(HOST_INTERNAL, false));

		assertNull(forB, "loader B must not be served loader A's aligned bytes");
		assertTrue(AnnotationTransformer.pendingAlignedClasses.containsKey(HOST_INTERNAL),
		 "loader B's miss must leave loader A's pending entry untouched");

		byte[] forA = new AnnotationTransformer().transform(loaderA, HOST_INTERNAL, null, loaderA.domain, classBytes(HOST_INTERNAL, false));

		assertNotNull(forA, "the owner loader must still be served (the isolation must not turn into a blanket miss)");
		assertEquals(true, hasAlignedMarker(forA), "loader A gets exactly the aligned bytes");
		assertArrayEquals(aligned, forA, "loader A gets exactly the aligned bytes");
		assertFalse(AnnotationTransformer.pendingAlignedClasses.containsKey(HOST_INTERNAL),
		 "consuming on the owner loader clears both name forms (same cleanup timing as before)");
		assertFalse(AnnotationTransformer.pendingAlignedClasses.containsKey(HOST_DOT),
		 "consuming on the owner loader clears both name forms (slash + dot)");
	}

	/** 加载器未知（ANY_SCOPE）登记：单 loader 场景下行为与引入 scope 之前逐字节一致。 */
	@Test
	void loaderUnknownRegistrationStillAppliesToWhicheverLoaderLoadsFirst() throws Exception {
		FixtureLoader loaderA = newLoader();
		byte[] aligned = classBytes(HOST_INTERNAL, true);
		AnnotationTransformer.pendingAlignedClasses.put(HOST_INTERNAL, aligned);
		AnnotationTransformer.pendingAlignedClasses.put(HOST_DOT, aligned);

		byte[] served = new AnnotationTransformer().transform(loaderA, HOST_INTERNAL, null, loaderA.domain, classBytes(HOST_INTERNAL, false));

		assertArrayEquals(aligned, served, "a registration made before the loader was known must still apply");
		assertFalse(AnnotationTransformer.pendingAlignedClasses.containsKey(HOST_INTERNAL), "consumed once");
	}

	/** 生产登记路径（{@code AlignmentTransaction.preRegister}）必须落在宿主的 loader scope 上。 */
	@Test
	void preRegisterScopesTheEntryToTheHostLoader() throws Exception {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		Class<?> host = loaderA.define("probe.IsoAlign", classBytes("probe/IsoAlign", false));
		RemoldAgent.loadedClassesMap.put("probe.IsoAlign", host);

		RemoldAgent.AlignmentTransaction tx = new RemoldAgent.AlignmentTransaction("probe.IsoAlign");
		tx.pendingAdds.put(HOST_DOT, classBytes(HOST_INTERNAL, true));
		tx.preRegister();

		assertTrue(AnnotationTransformer.pendingAlignedClasses.containsKey(HOST_INTERNAL),
		 "the union view still reports the pending class (slot avoidance depends on it)");

		assertNull(new AnnotationTransformer().transform(loaderB, HOST_INTERNAL, null, loaderB.domain, classBytes(HOST_INTERNAL, false)),
		 "preRegister must not hand the host's aligned bytes to another loader");

		byte[] served = new AnnotationTransformer().transform(loaderA, HOST_INTERNAL, null, loaderA.domain, classBytes(HOST_INTERNAL, false));

		assertEquals(true, hasAlignedMarker(served), "the host's own loader still gets the aligned bytes");
		RemoldAgent.loadedClassesMap.remove("probe.IsoAlign");
	}

	/** 自造名暂存同样按 loader 分键：A 的暂存不能被 B 的收尾取走。 */
	@Test
	void pendingInventedNamesAreNotReadableAcrossLoaders() {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		long scopeA = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA);
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);
		assertTrue(scopeA != scopeB, "precondition: two loaders must map to two distinct scopes");

		RemoldAgent.pendingInvented.put(scopeA, INV, Set.of("lambda$iso$0"));

		assertNull(RemoldAgent.pendingInvented.remove(scopeB, INV),
		 "loader B must not consume loader A's invented slot names");
		assertEquals(Set.of("lambda$iso$0"), RemoldAgent.pendingInvented.remove(scopeA, INV),
		 "the owner loader still gets its own names");
	}

	/** 写入/消费都只拿类名时（生产路径）：同一 Class 的写入与收尾必须对上，跨 loader 不得串用。 */
	@Test
	void pendingInventedNameOnlyPathStaysConsistentForTheSameClass() {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		Class<?> target = loaderA.define(INV, classBytes(INV.replace('.', '/'), false));
		RemoldAgent.loadedClassesMap.put(INV, target);
		long scopeA = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA);
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);

		RemoldAgent.pendingInvented.put(INV, Set.of("lambda$iso$1"));

		assertNull(RemoldAgent.pendingInvented.remove(scopeB, INV),
		 "the name-only write must still be scoped to the class's own loader");
		assertEquals(Set.of("lambda$iso$1"), RemoldAgent.pendingInvented.remove(INV),
		 "the success path (name only) must find what the alignment path (name only) stored");
		assertEquals(scopeA, AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA),
		 "precondition: the resolved scope is stable across the two name-only calls");
	}

	// ------------------------------------------------------------ 辅助

	private FixtureLoader newLoader() {
		try {
			FixtureLoader loader = new FixtureLoader();
			loaders.add(loader);
			return loader;
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] classBytes(String internalName, boolean aligned) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		if (aligned) {
			cw.visitField(Opcodes.ACC_PUBLIC, "alignedMarker", "I", null, null).visitEnd();
		}
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static boolean hasAlignedMarker(byte[] bytes) {
		if (bytes == null) return false;
		final boolean[] found = {false};
		new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc,
			                                                String signature, Object value) {
				if ("alignedMarker".equals(name)) found[0] = true;
				return null;
			}
		}, 0);
		return found[0];
	}

	/** 工作区里的独立加载器（{@code ProtectionDomain} 指向受监控目录，出生补丁的归属校验才放行）。 */
	private static final class FixtureLoader extends URLClassLoader {
		final Path projectDir;
		final ProtectionDomain domain;

		FixtureLoader() throws Exception {
			this(Files.createTempDirectory("remold-pending-iso"));
		}

		private FixtureLoader(Path projectDir) throws Exception {
			super(new URL[] {projectDir.toUri().toURL()}, PendingLoaderIsolationTest.class.getClassLoader());
			this.projectDir = projectDir;
			CodeSource cs = new CodeSource(URI.create(projectDir.toUri().toString()).toURL(), (Certificate[]) null);
			this.domain = new ProtectionDomain(cs, null);
			RemoldAgent.activeWatchDirs.add(projectDir);
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}
}

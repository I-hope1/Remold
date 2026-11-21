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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自造槽位名的<b>生产写入 / 成功回执</b>两侧都按 loader scope 分键（{@code docs/status.md} §5 风险 15）。
 *
 * <p><b>缺陷现场</b>：{@code BatchHotswapExecutor} 在已有 {@code alignLoader} 的情况下仍
 * {@code pendingInvented.put(className, …)}（纯类名），{@code commitInvented} 也按纯类名取并写纯类名的
 * {@code inventedSlots}。两个 loader 各有一个同名类时，后写入的暂存会顶掉前一个，A 的成功回执把 B 的
 * 自造名当成自己的落账 → 下一轮 B 的 lambda 按 A 的槽位名配对（静默跑错）；更糟的是 A 的回执在
 * "本轮没有暂存"时用<b>按名字删所有 scope</b> 的旧语义，会顺手抹掉 B 的历史自造名。</p>
 *
 * <p><b>为什么这条用例必须驱生产路径</b>：{@code PendingLoaderIsolationTest} 直接测
 * {@code (scope, name)} 重载，证明不了"生产写入/回执真的走了重载"。这里调的是
 * {@link BatchHotswapExecutor#stageInventedNames}（对齐期唯一的写入入口）与
 * {@link RemoldAgent#commitInvented(long, String)}（唯一的回执入口）——把写入退回纯类名，本用例必红。</p>
 */
class InventedSlotScopeTest {

	private static final String INV = "probe.ScopeInvented";

	private final List<FixtureLoader> loaders = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		RemoldAgent.pendingInvented.clear();
		RemoldAgent.inventedSlots.clear();
		RemoldAgent.loadedClassesMap.remove(INV);
		loaders.clear();
	}

	/**
	 * 两个 loader 同名类：各写各的暂存、各收各的回执，互不覆盖。
	 *
	 * <p>前置基线断言两侧 scope 确实不同、且暂存确实在<b>写入之后</b>分别存在——否则"互不覆盖"可能只是
	 * "两边都没有"的假阳性。</p>
	 */
	@Test
	void productionWriteAndReceiptKeepEachLoadersInventedNames() throws Exception {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		Class<?> classA = loaderA.define(INV, classBytes(INV));
		Class<?> classB = loaderB.define(INV, classBytes(INV));
		RemoldAgent.loadedClassIndex().put(AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA), INV, classA);
		RemoldAgent.loadedClassIndex().put(AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB), INV, classB);
		long scopeA = RemoldAgent.loaderScope(loaderA);
		long scopeB = RemoldAgent.loaderScope(loaderB);
		assertTrue(scopeA != scopeB, "precondition: the two loaders must map to two distinct scopes");
		assertNull(RemoldAgent.pendingInvented.lookup(scopeA, INV),
		 "precondition: neither loader has staged names before this round");

		// 生产写入路径（对齐期）：两边各算出一组自造名。
		BatchHotswapExecutor.stageInventedNames(scopeA, INV, Set.of("lambda$scope$A"));
		BatchHotswapExecutor.stageInventedNames(scopeB, INV, Set.of("lambda$scope$B"));

		assertEquals(Set.of("lambda$scope$A"), RemoldAgent.pendingInvented.lookup(scopeA, INV),
		 "precondition: loader A's staged names survive loader B's write (they are keyed per scope)");
		assertEquals(Set.of("lambda$scope$B"), RemoldAgent.pendingInvented.lookup(scopeB, INV),
		 "precondition: loader B's staged names are stored separately");

		// 生产回执路径（redefine 成功后）：按同一 scope 取走并落账。
		RemoldAgent.commitInvented(scopeA, INV);
		RemoldAgent.commitInvented(scopeB, INV);

		assertEquals(Set.of("lambda$scope$A"), RemoldAgent.INVENTED_SLOTS.lookup(scopeA, INV),
		 "loader A's committed slot names must be its own");
		assertEquals(Set.of("lambda$scope$B"), RemoldAgent.INVENTED_SLOTS.lookup(scopeB, INV),
		 "loader B's committed slot names must be its own (A's receipt must not overwrite them)");
		assertNull(RemoldAgent.pendingInvented.get(INV),
		 "both staged entries must be consumed by their own receipts: " + RemoldAgent.pendingInvented.get(INV));
	}

	/**
	 * 空暂存的回执不得抹掉别的 loader 的历史自造名。
	 *
	 * <p>旧实现 {@code inventedSlots.remove(className)} 是按名字删所有 scope：A 这一轮没有新暂存时
	 * 的回执会把 B 上一轮落账的自造名一起删掉，下一轮 B 的 lambda 就按 javac 原始编号配对。</p>
	 */
	@Test
	void receiptWithoutPendingDoesNotWipeAnotherLoadersNames() throws Exception {
		FixtureLoader loaderA = newLoader();
		FixtureLoader loaderB = newLoader();
		Class<?> classA = loaderA.define(INV, classBytes(INV));
		Class<?> classB = loaderB.define(INV, classBytes(INV));
		long scopeA = RemoldAgent.loaderScope(loaderA);
		long scopeB = RemoldAgent.loaderScope(loaderB);
		RemoldAgent.loadedClassIndex().put(scopeA, INV, classA);
		RemoldAgent.loadedClassIndex().put(scopeB, INV, classB);

		BatchHotswapExecutor.stageInventedNames(scopeB, INV, Set.of("lambda$kept"));
		RemoldAgent.commitInvented(scopeB, INV);
		assertEquals(Set.of("lambda$kept"), RemoldAgent.INVENTED_SLOTS.lookup(scopeB, INV),
		 "precondition: loader B owns a committed invented name before A's receipt runs");

		// A 这一轮没有任何自造名（纯方法体修改）：回执不得动 B 的条目。
		RemoldAgent.commitInvented(scopeA, INV);

		assertEquals(Set.of("lambda$kept"), RemoldAgent.INVENTED_SLOTS.lookup(scopeB, INV),
		 "an empty receipt for loader A must not remove loader B's committed invented names");
		assertNull(RemoldAgent.INVENTED_SLOTS.lookup(scopeA, INV),
		 "loader A still has no invented names of its own — it must never see B's as its own");
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

	/**
	 * 调用点守卫：{@code BatchHotswapExecutor} 里只允许经 {@link BatchHotswapExecutor#stageInventedNames}
	 * 写入自造名，且生产调用点必须把 scope 传进去。
	 *
	 * <p>只测辅助方法是不够的——把调用点改回 {@code pendingInvented.put(className, …)} 时上面的用例
	 * 照样绿，因为它是直接调辅助方法；这里锁的是"真实调用点没被换掉"。</p>
	 */
	@Test
	void productionCallSiteStagesInventedNamesWithAScope() throws Exception {
		java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
		while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
			root = root.getParent();
		}
		assertTrue(root != null, "cannot locate the repository root from " + java.nio.file.Path.of("").toAbsolutePath());
		String code = java.nio.file.Files.readString(
		 root.resolve("core/src/remold/BatchHotswapExecutor.java"), java.nio.charset.StandardCharsets.UTF_8);
		// 允许 stageInventedNames 自己那一处写入，但任何一处都必须带 scope 作为第一个实参：
		// 退回纯类名时第一实参就是 className，这里会立刻红。
		java.util.regex.Matcher direct = java.util.regex.Pattern
		 .compile("pendingInvented\\s*\\.\\s*put\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)").matcher(code);
		while (direct.find()) {
			assertTrue(direct.group(1).toLowerCase(java.util.Locale.ROOT).contains("scope"),
			 "invented names must be staged with a loader scope, not by bare class name: "
			  + direct.group(1));
		}
		java.util.regex.Matcher calls = java.util.regex.Pattern
		 .compile("stageInventedNames\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)").matcher(code);
		int sites = 0;
		while (calls.find()) {
			String before = code.substring(Math.max(0, calls.start() - 40), calls.start());
			if (before.contains("void ")) continue;   // 方法声明本身不是调用点
			sites++;
			String firstArgument = calls.group(1);
			assertTrue(firstArgument.toLowerCase(java.util.Locale.ROOT).contains("scope"),
			 "the production call site must pass a loader scope, was '" + firstArgument + "'");
		}
		assertTrue(sites >= 1, "the align flow must stage invented names through stageInventedNames");
	}

	private static byte[] classBytes(String dotName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, dotName.replace('.', '/'), null, "java/lang/Object", null);
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

	/** 工作区里的独立加载器（与生产 pending 归属校验同一形态）。 */
	private static final class FixtureLoader extends URLClassLoader {
		final Path projectDir;
		final ProtectionDomain domain;

		FixtureLoader() throws Exception {
			this(Files.createTempDirectory("remold-invented-scope"));
		}

		private FixtureLoader(Path projectDir) throws Exception {
			super(new URL[] {projectDir.toUri().toURL()}, InventedSlotScopeTest.class.getClassLoader());
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

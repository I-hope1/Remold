package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.vcs.AlignmentMetaStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoadedClassIndex} 的 scope 维度回归锁：同一个类名在两个 loader 下各有一份时，
 * <b>互不覆盖</b>，且"仅类名"查询在歧义时返回空 + 告警（绝不随便挑一个）。
 *
 * <p>成因见 {@code docs/status.md} §5 风险 15：{@code loadedClassesMap} 原来是纯类名键，
 * 后登记的副本会顶掉先登记的；{@code pendingInvented} 的"仅类名"生产路径靠它反查 loader，
 * 于是 A 算出的自造名会被 B 的成功回执取走。</p>
 *
 * <p>对照（防空转）：同名双 loader 是<b>去触发</b>的判据——单 loader 下的唯一类名必须照常可查；
 * 歧义只由"第二份副本存在"造成，而不是索引一律返回 null。</p>
 */
class LoadedClassIndexScopeTest {

	private static final String INTERNAL = "probe/scope/DualTarget";
	private static final String DOT = "probe.scope.DualTarget";

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final RecordingLogger recorder = new RecordingLogger();
	private LoadedClassIndex originalIndex;

	@BeforeEach
	void install() {
		RemoldAgent.logger = this.recorder;
		this.originalIndex = RemoldAgent.loadedClassIndex();
	}

	@AfterEach
	void restore() {
		RemoldAgent.loadedClassesMap = this.originalIndex;
		RemoldAgent.logger = this.originalLogger;
	}

	/**
	 * 两个 loader 各自加载同名类、分别登记：各自查自己拿到自己的；"仅类名"查询歧义 → 空 + 告警。
	 */
	@Test
	void sameNameInTwoLoadersIsKeptPerScopeAndNameOnlyLookupRefusesToGuess() throws Exception {
		FixtureLoader loaderA = new FixtureLoader("a");
		FixtureLoader loaderB = new FixtureLoader("b");
		Class<?> classA = loaderA.define(DOT, classBytes("a"));
		Class<?> classB = loaderB.define(DOT, classBytes("b"));
		// 前置基线：两份副本确实是同一个名字、不同 loader、不同字节。
		assertEquals(DOT, classA.getName());
		assertEquals(DOT, classB.getName());
		assertSame(loaderA, classA.getClassLoader());
		assertSame(loaderB, classB.getClassLoader());
		assertEquals("a", classA.getMethod("mark").invoke(null), "loader A must really hold its own copy");
		assertEquals("b", classB.getMethod("mark").invoke(null), "loader B must really hold its own copy");

		long scopeA = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA);
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);
		assertTrue(scopeA != scopeB, "two loaders must get different scopes");

		LoadedClassIndex index = new LoadedClassIndex();
		index.put(scopeA, DOT, classA);
		// 第二轮登记同名类：纯类名键的实现会在这里顶掉 A 那份。
		index.put(scopeB, DOT, classB);

		assertEquals(2, index.copyCount(DOT), "both copies must be stored, not overwritten");
		assertSame(classA, index.get(scopeA, DOT), "scope A must resolve its own copy");
		assertSame(classB, index.get(scopeB, DOT), "scope B must resolve its own copy");
		assertSame(classA, index.get(scopeA, DOT), "a second exact lookup must not drift to the other loader");
		assertSame(loaderB, index.get(scopeB, DOT).getClassLoader(),
		 "the exact lookup must hand back the class of that very loader, so a name -> loader back-reference is safe");

		// "仅类名"降级语义：歧义 → null + 告警（不是"随便挑一个"）。
		assertNull(index.get(DOT), "an ambiguous name-only lookup must return null, never an arbitrary copy");
		assertTrue(this.recorder.warns.stream().anyMatch(w -> w.contains("[LOADED-NAME-AMBIGUOUS]")
			 && w.contains(DOT) && w.contains("scope=" + scopeA) && w.contains("scope=" + scopeB)),
		 "the ambiguity must be user visible and name both scopes: " + this.recorder.warns);

		// 对照：换一个只有单份副本的无关类名，同一套查询必须照常返回（歧义不是"一律 null"）。
		LoadedClassIndex single = new LoadedClassIndex();
		single.put(scopeA, "probe.scope.SingleTarget", classA);
		assertSame(classA, single.get("probe.scope.SingleTarget"),
		 "a name with exactly one copy must still resolve through the name-only lookup");
	}

	/**
	 * 生产快照路径：{@code loadClassSnap} 也必须按 scope 存两份，而不是把先到的那份顶掉。
	 */
	@Test
	void productionSnapshotKeepsBothLoaderCopies() throws Exception {
		FixtureLoader loaderA = new FixtureLoader("a");
		FixtureLoader loaderB = new FixtureLoader("b");
		Class<?> classA = loaderA.define(DOT, classBytes("a"));
		Class<?> classB = loaderB.define(DOT, classBytes("b"));

		RemoldAgent.loadClassSnap(new Class<?>[] {classA, classB});

		LoadedClassIndex snapshot = RemoldAgent.loadedClassIndex();
		long scopeA = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderA);
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);
		assertEquals(2, snapshot.copyCount(DOT), "the snapshot must keep both loader copies");
		assertSame(classA, snapshot.get(scopeA, DOT));
		assertSame(classB, snapshot.get(scopeB, DOT));
		assertNull(snapshot.get(DOT), "the snapshot's name-only lookup must stay ambiguous, not pick a copy");
		assertTrue(this.recorder.warns.stream().anyMatch(w -> w.contains("[LOADED-NAME-AMBIGUOUS]")),
		 "the snapshot's ambiguity must be warned about as well: " + this.recorder.warns);
	}

	@Test
	void ephemeralPredictionCopyAloneDoesNotEmitArbitratedWarning() throws Exception {
		byte[] bytes = classBytes("live");
		FixtureLoader liveLoader = new FixtureLoader("live");
		Class<?> live = liveLoader.define(DOT, bytes);
		remold.offset.LayoutPredictor.EphemeralClassLoader ephemeral =
			new remold.offset.LayoutPredictor.EphemeralClassLoader(
				java.util.Map.of(INTERNAL, bytes), null, liveLoader);
		Class<?> ephemeralCopy = ephemeral.loadClass(DOT);

		long liveScope = AlignmentMetaStore.INSTANCE.loaderScopeId(liveLoader);
		long ephScope = AlignmentMetaStore.INSTANCE.loaderScopeId(ephemeral);

		LoadedClassIndex index = new LoadedClassIndex();
		index.put(liveScope, DOT, live);
		index.put(ephScope, DOT, ephemeralCopy);

		assertSame(live, index.get(DOT), "the live class must win over EphemeralClassLoader");
		assertTrue(this.recorder.warns.isEmpty(),
			"an internal EphemeralClassLoader copy must not emit noisy [LOADED-NAME-ARBITRATED] warnings: "
				+ this.recorder.warns);
	}

	/** 合法 class 字节：一个 {@code mark()} 返回区分字符串，用来证明两份副本真的是两份。 */
	private static byte[] classBytes(String mark) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INTERNAL, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor markMethod = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mark",
		 "()Ljava/lang/String;", null, null);
		markMethod.visitCode();
		markMethod.visitLdcInsn(mark);
		markMethod.visitInsn(Opcodes.ARETURN);
		markMethod.visitMaxs(1, 0);
		markMethod.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader(String name) {
			super(name, LoadedClassIndexScopeTest.class.getClassLoader());
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}

	private static final class RecordingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();

		@Override public void log(String msg) { }
		@Override public void info(String msg) { }
		@Override public void warn(String msg) { this.warns.add(msg); }
		@Override public void error(String msg) { }
		@Override public void error(String msg, Throwable t) { }
	}
}

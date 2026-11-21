package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 被 class 文件合法性门禁拒掉的类，其对齐事务<b>不许提交</b>（{@code docs/status.md} §5 风险 15/16）。
 *
 * <p><b>缺陷现场</b>：{@code applyTwoPhase} / {@code applyRedefinitions} 只把门禁过滤后的
 * {@code definitions} 传下去，{@code transactions} 原样传入；{@code TwoPhaseCoordinator} 成功后对
 * <b>所有</b>事务 {@code commit()}，空批次分支甚至直接 {@code commit()}。而
 * {@link RemoldAgent.AlignmentTransaction#commit()} 会 {@code preRegister()} 写
 * {@code pendingAlignedClasses}、把 {@code cacheUpdates} 写进 {@code bytecodeCache}——于是
 * <b>没进 redefineClasses 的类，其未生效字节反而成了基线</b>，下一轮对齐把它当"已生效版本"。</p>
 *
 * <p><b>断言全部落在终局</b>：事务是否真的提交（会影响后续所有轮次的持久化动作）、
 * {@code pendingAlignedClasses} / {@code bytecodeCache} 里有没有新条目、以及 JVM 实际收到的
 * {@code ClassDefinition} 个数。前置基线断言"这类确实被门禁拒了"，否则"没有新条目"可能只是
 * "根本没走到这里"的假阳性。</p>
 */
class BytecodeGateTransactionTest {

	private static final String GOOD_INTERNAL = "probe/TxGateGood";
	private static final String GOOD_DOT = "probe.TxGateGood";
	private static final String GOOD_ANON_INTERNAL = "probe/TxGateGood$1";
	private static final String GOOD_ANON_DOT = "probe.TxGateGood$1";
	private static final String BAD_INTERNAL = "probe/TxGateBad";
	private static final String BAD_DOT = "probe.TxGateBad";
	private static final String BAD_ANON_INTERNAL = "probe/TxGateBad$1";
	private static final String BAD_ANON_DOT = "probe.TxGateBad$1";

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final Instrumentation originalInstrumentation = RemoldAgent.getInstrumentation();
	private final RecordingLogger recorder = new RecordingLogger();
	private final RecordingInstrumentation instrumentation = new RecordingInstrumentation();

	@BeforeEach
	void install() {
		RemoldAgent.logger = this.recorder;
		RemoldAgent.swapInstrumentationForTest(this.instrumentation.proxy);
	}

	@AfterEach
	void cleanUp() {
		RemoldAgent.swapInstrumentationForTest(this.originalInstrumentation);
		RemoldAgent.logger = this.originalLogger;
		AnnotationTransformer.pendingAlignedClasses.clear();
		for (String key : List.of(GOOD_DOT, BAD_DOT, GOOD_ANON_DOT, BAD_ANON_DOT)) {
			RemoldAgent.bytecodeCache.remove(key);
			RemoldAgent.inventedSlots.remove(key);
		}
		RemoldAgent.loadedClassesMap.remove(GOOD_DOT);
		RemoldAgent.loadedClassesMap.remove(BAD_DOT);
	}

	// ------------------------------------------------------------ M1 正向：一个类通过、一个类被拒

	/**
	 * 混合批次：通过类的事务照常提交，被拒类的事务一条都不许提交。
	 *
	 * <p>被拒类是"常量池被截断"的字节（魔数与长度都合法，只有逐项走常量池才发现），
	 * 走的是真实入口 {@link RemoldAgent#applyRedefinitions}，与生产路径同一函数。</p>
	 */
	@Test
	void rejectedClassTransactionIsNotCommittedWhileItsSiblingCommits() {
		byte[] goodBytes = validBytes(GOOD_INTERNAL);
		byte[] badWhole = validBytes(BAD_INTERNAL);
		byte[] badBytes = Arrays.copyOf(badWhole, 16);
		Class<?> goodTarget = define(GOOD_INTERNAL, goodBytes);
		Class<?> badTarget = define(BAD_INTERNAL, badWhole);
		ClassDefinition goodDefinition = new ClassDefinition(goodTarget, goodBytes);
		ClassDefinition badDefinition = new ClassDefinition(badTarget, badBytes);

		byte[] goodAligned = classBytes(GOOD_ANON_INTERNAL);
		byte[] badAligned = classBytes(BAD_ANON_INTERNAL);
		RemoldAgent.AlignmentTransaction goodTransaction = transaction(GOOD_DOT, GOOD_ANON_INTERNAL,
		 GOOD_ANON_DOT, goodAligned);
		RemoldAgent.AlignmentTransaction badTransaction = transaction(BAD_DOT, BAD_ANON_INTERNAL,
		 BAD_ANON_DOT, badAligned);

		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		RemoldAgent.RedefineOutcome outcome = RemoldAgent.applyRedefinitions(
		 List.of(goodDefinition, badDefinition), List.of(goodTransaction, badTransaction), skips);

		// 前置基线：被拒类确实是被门禁拦下的（否则"没有新条目"只说明没走到这条路径）。
		String verdict = skips.get(BAD_DOT);
		assertNotNull(verdict, "precondition: the malformed class must be recorded as refused: " + skips);
		assertTrue(verdict.startsWith("BYTECODE-GATE"), "precondition: refusal comes from this gate: " + verdict);
		assertTrue(skips.bytecodeRejected().contains(BAD_DOT),
		 "precondition: the structured refusal list must name the rejected class: " + skips.bytecodeRejected());

		// 终局 1：被拒类的事务没有提交，通过类的提交了。
		assertFalse(badTransaction.committed,
		 "the transaction of a class the gate refused must never be committed"
				+ " — committing it publishes bytecode that never reached the JVM as the next baseline");
		assertTrue(goodTransaction.committed, "the admitted class's transaction still commits: " + outcome);

		// 终局 2：被拒类的 pending / cache 都没有新条目。
		assertNull(AnnotationTransformer.pendingAlignedClasses.get(BAD_ANON_DOT),
		 "the refused class's aligned bytes must not be registered in pendingAlignedClasses");
		assertNull(AnnotationTransformer.pendingAlignedClasses.get(BAD_ANON_INTERNAL),
		 "the refused class's aligned bytes must not be registered in pendingAlignedClasses");
		assertNull(RemoldAgent.bytecodeCache.get(BAD_ANON_DOT),
		 "the refused class's bytes must not enter bytecodeCache (that table is read as the live baseline)");

		// 终局 3：通过类的两条登记都在（证明修复不是"一律不提交"）。
		assertArrayEquals(goodAligned, AnnotationTransformer.pendingAlignedClasses.get(GOOD_ANON_INTERNAL),
		 "the admitted class's aligned bytes must be registered (slash key)");
		assertArrayEquals(goodAligned, AnnotationTransformer.pendingAlignedClasses.get(GOOD_ANON_DOT),
		 "the admitted class's aligned bytes must be registered (dot key)");
		assertArrayEquals(goodAligned, RemoldAgent.bytecodeCache.get(GOOD_ANON_DOT),
		 "the admitted class's bytes must reach bytecodeCache");

		// 终局 4：JVM 只收到了通过类那一个定义。
		assertEquals(1, this.instrumentation.redefinitions.size(),
		 "only the admitted class may reach redefineClasses: " + this.instrumentation.redefinitions.size());
		assertSame(goodDefinition, this.instrumentation.redefinitions.get(0)[0],
		 "the admitted definition is the one handed to the JVM");
		assertTrue(outcome.successful().contains(GOOD_DOT), "the admitted class is reported as redefined: " + outcome);
		assertFalse(outcome.successful().contains(BAD_DOT), "the refused class is not reported as redefined: " + outcome);
	}

	/**
	 * 匿名类被拒：宿主组整组不提交。
	 *
	 * <p>被拒的是宿主组里的匿名类（宿主的对齐字节本来要随事务登记）。只丢被拒的那一条、照常提交
	 * 其余条目，会留下"宿主已上线、其匿名类字节还是上一轮"的半套状态——那正是
	 * {@code AlignmentTransaction} 存在的理由（组原子性）。</p>
	 */
	@Test
	void rejectedAnonymousClassKeepsItsWholeGroupOutOfTheCommit() {
		byte[] hostBytes = validBytes(GOOD_INTERNAL);
		byte[] anonWhole = validBytes(GOOD_ANON_INTERNAL);
		byte[] anonTruncated = Arrays.copyOf(anonWhole, 16);
		Class<?> host = define(GOOD_INTERNAL, hostBytes);
		Class<?> anon = define(GOOD_ANON_INTERNAL, anonWhole);
		ClassDefinition hostDefinition = new ClassDefinition(host, hostBytes);
		ClassDefinition anonDefinition = new ClassDefinition(anon, anonTruncated);

		byte[] aligned = classBytes(GOOD_ANON_INTERNAL);
		RemoldAgent.AlignmentTransaction transaction =
		 transaction(GOOD_DOT, GOOD_ANON_INTERNAL, GOOD_ANON_DOT, aligned);

		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		RemoldAgent.applyRedefinitions(List.of(hostDefinition, anonDefinition), List.of(transaction), skips);

		assertTrue(skips.bytecodeRejected().contains(GOOD_ANON_DOT),
		 "precondition: the anonymous class is the one the gate refused: " + skips.bytecodeRejected());
		assertFalse(transaction.committed,
		 "a group holding a refused member must not be committed as a whole");
		assertNull(AnnotationTransformer.pendingAlignedClasses.get(GOOD_ANON_INTERNAL),
		 "no fresh aligned bytes may be registered for the refused anonymous class");
		assertNull(RemoldAgent.bytecodeCache.get(GOOD_ANON_DOT),
		 "no fresh aligned bytes may enter bytecodeCache for the refused anonymous class");
		assertTrue(skips.contains(GOOD_DOT),
		 "the host must be named in the round's skip ledger when its group is dropped: " + skips);
	}

	/**
	 * 两阶段入口同样要在 stage A 的 {@code preRegister} 之前裁掉被拒类的事务。
	 *
	 * <p>两阶段路径的提交点分散在 stage A 的 {@code preRegister}（登记 pending）与 stage C 的
	 * {@code commit()}（写 bytecodeCache）两处，只修单阶段会在这里留下后门：被拒类的对齐字节照样在
	 * stage A 之前被登记进 pending，下一轮首次加载就拿到从未生效的字节。</p>
	 *
	 * <p><b>为什么要在 redefineClasses 那一刻采样</b>：事后看最终态是干净的——stage A 的组一致性
	 * 检查会把整组 rollback 掉。但违规登记<b>存在过</b>一段时间窗口，窗口里任何线程首次加载该类
	 * 都会拿到从未生效的字节。所以断言落在"进 redefineClasses 那一刻 pending 里有没有它"，
	 * 而不是"函数返回后有没有它"。</p>
	 */
	@Test
	void rejectedClassTransactionStaysOutOfTheTwoPhasePreRegister() {
		byte[] goodBytes = validBytes(GOOD_INTERNAL);
		Class<?> goodTarget = define(GOOD_INTERNAL, goodBytes);
		ClassDefinition goodDefinition = new ClassDefinition(goodTarget, goodBytes);

		byte[] badAligned = classBytes(BAD_ANON_INTERNAL);
		RemoldAgent.AlignmentTransaction badTransaction =
		 transaction(BAD_DOT, BAD_ANON_INTERNAL, BAD_ANON_DOT, badAligned);

		// 前置基线：本轮的拒绝名单里确实有这个宿主（与生产路径上 gate 记进台账的形态一致）。
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		skips.markBytecodeRejected(BAD_DOT);
		assertTrue(skips.bytecodeRejected().contains(BAD_DOT), "precondition: the refusal is recorded");

		this.instrumentation.watchedPendingName = BAD_ANON_INTERNAL;
		RemoldAgent.RedefineOutcome outcome = TwoPhaseCoordinator.applyTwoPhase(
		 List.of(goodDefinition), Map.of(), List.of(badTransaction), skips, Set.of());

		assertFalse(this.instrumentation.pendingAtRedefine.isEmpty(),
		 "precondition: stage A really entered redefineClasses, so the window was sampled");
		for (byte[] seen : this.instrumentation.pendingAtRedefine) {
			assertNull(seen, "the refused group's bytes must never be observable in pendingAlignedClasses,"
				+ " not even while stage A runs");
		}
		assertFalse(badTransaction.committed,
		 "the two-phase path must not commit the transaction of a refused class: " + outcome);
		assertNull(AnnotationTransformer.pendingAlignedClasses.get(BAD_ANON_INTERNAL),
		 "stage A's preRegister must never run for a refused group (pending would publish unapplied bytes)");
		assertNull(RemoldAgent.bytecodeCache.get(BAD_ANON_DOT),
		 "stage C must never write the refused group's bytes into bytecodeCache");
	}

	// ------------------------------------------------------------ M1 反向：空批次必须回滚

	/**
	 * 空批次语义：{@code definitions} 为空时**回滚**，不是"无事发生地提交"。
	 *
	 * <p>直接驱动 {@code TwoPhaseCoordinator}，因为这条分支的输入就是"一个都没生效"：
	 * 生产路径上它由"全部类被门禁拒 / 全部被 JVM 级熔断过滤"造成。</p>
	 */
	@Test
	void emptyRoundRollsBackInsteadOfCommitting() {
		byte[] fresh = classBytes(GOOD_ANON_INTERNAL);
		byte[] old = classBytes(BAD_ANON_INTERNAL);
		RemoldAgent.AlignmentTransaction transaction = transaction(GOOD_DOT, GOOD_ANON_INTERNAL,
		 GOOD_ANON_DOT, fresh);
		transaction.pinOldBytes.put(GOOD_ANON_DOT, old);

		RemoldAgent.RedefineOutcome outcome = TwoPhaseCoordinator.applyRedefinitions(
		 List.of(), List.of(transaction), new RemoldAgent.RoundSkips());

		assertFalse(transaction.committed,
		 "an empty round must roll the transaction back — committing publishes bytes that never reached the JVM");
		assertTrue(outcome.successful().isEmpty(), "nothing was redefined: " + outcome);
		assertNull(RemoldAgent.bytecodeCache.get(GOOD_ANON_DOT),
		 "an empty round must not write bytecodeCache: " + RemoldAgent.bytecodeCache.get(GOOD_ANON_DOT));
		byte[] pinned = AnnotationTransformer.pendingAlignedClasses.get(GOOD_ANON_INTERNAL);
		assertArrayEquals(old, pinned,
		 "rollback must pin the OLD (live) bytes so a later first load cannot take the unapplied new ones");
		assertFalse(Arrays.equals(fresh, pinned), "the unapplied new bytes must never be pinned: " + fresh.length);
	}

	/** 同一契约的两阶段形式：stage A 前就空了，也必须整轮回滚。 */
	@Test
	void emptyTwoPhaseRoundRollsBackInsteadOfCommitting() {
		byte[] fresh = classBytes(GOOD_ANON_INTERNAL);
		byte[] old = classBytes(BAD_ANON_INTERNAL);
		RemoldAgent.AlignmentTransaction transaction = transaction(GOOD_DOT, GOOD_ANON_INTERNAL,
		 GOOD_ANON_DOT, fresh);
		transaction.pinOldBytes.put(GOOD_ANON_DOT, old);

		RemoldAgent.RedefineOutcome outcome = TwoPhaseCoordinator.applyTwoPhase(
		 List.of(), Map.of(), List.of(transaction), new RemoldAgent.RoundSkips(), Set.of());

		assertFalse(transaction.committed, "an empty two-phase round must roll back, not commit");
		assertTrue(outcome.successful().isEmpty(), "nothing was redefined: " + outcome);
		assertNull(RemoldAgent.bytecodeCache.get(GOOD_ANON_DOT),
		 "an empty two-phase round must not write bytecodeCache");
		assertArrayEquals(old, AnnotationTransformer.pendingAlignedClasses.get(GOOD_ANON_INTERNAL),
		 "the live bytes must be the ones pinned for future first loads");
	}

	// ------------------------------------------------------------ 辅助

	private static RemoldAgent.AlignmentTransaction transaction(String hostDot, String anonInternal,
	                                                          String anonDot, byte[] aligned) {
		RemoldAgent.AlignmentTransaction transaction = new RemoldAgent.AlignmentTransaction(hostDot);
		transaction.pendingAdds.put(anonInternal, aligned);
		transaction.cacheUpdates.put(anonDot, aligned);
		transaction.targetClasses.add(anonDot);
		return transaction;
	}

	private static Class<?> define(String internalName, byte[] bytes) {
		return new FixtureLoader(BytecodeGateTransactionTest.class.getClassLoader())
		 .define(internalName.replace('/', '.'), bytes);
	}

	/** 自造一份合法 class 文件（含构造器，便于 {@code defineClass} 直接定义）。 */
	private static byte[] validBytes(String internalName) {
		return classBytes(internalName);
	}

	private static byte[] classBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
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

	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}

	private static final class RecordingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override public void log(String msg) { }
		@Override public void info(String msg) { }
		@Override public void warn(String msg) { this.warns.add(msg); }
		@Override public void error(String msg) { this.errors.add(msg); }
		@Override public void error(String msg, Throwable t) { this.errors.add(msg + ": " + t); }
	}

	/** 只录制 {@code redefineClasses}；不真的重定义（测试用的类与字节码不同源）。 */
	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition[]> redefinitions = new ArrayList<>();
		/** 采样：每次调 {@code redefineClasses} 时，{@link #watchedPendingName} 在 pending 里的字节。 */
		final List<byte[]> pendingAtRedefine = new ArrayList<>();
		String watchedPendingName;
		final Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(
		 BytecodeGateTransactionTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
				case "isRetransformClassesSupported":
				case "isModifiableClass":
				case "isRetransformClassesSupportedByDefault":
					return true;
				case "getAllLoadedClasses":
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "redefineClasses":
					if (this.watchedPendingName != null) {
						this.pendingAtRedefine.add(
							AnnotationTransformer.pendingAlignedClasses.get(this.watchedPendingName));
					}
					this.redefinitions.add((ClassDefinition[]) args[0]);
					return null;
				case "addTransformer":
				case "removeTransformer":
					return true;
				case "getObjectSize":
					return 0L;
				default:
					return defaultFor(method.getReturnType());
			}
		}

		private static Object defaultFor(Class<?> type) {
			if (!type.isPrimitive()) return null;
			if (type == boolean.class) return false;
			if (type == long.class) return 0L;
			return 0;
		}
	}
}

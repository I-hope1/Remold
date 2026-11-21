package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * callee overlap 证据（内容证据）：只在"唯一严格最大正交集"时介入配对，平票与噪声一律退回既有规则。
 *
 * <p>动机是跨代错配的对称性：位置同名只说明编译器把两个 lambda 排在同一序号上。删除
 * {@code $0} 并改写 {@code $1}，与删除 {@code $1} 并改写 {@code $0}，在只按序号配对时必然有一种是错的。
 * 内容证据（这个 lambda 到底调了谁）对两种镜像编辑同时成立，因此可以作为不偏袒任何一侧的判据。</p>
 *
 * <p>这里锁的是规则本身：唯一严格最大才介入、平票不介入、样板调用不构成证据。</p>
 */
class CalleeOverlapEvidenceTest {

	private static final String SUPER_REF = "test/BaseOps#baseM(Ljava/lang/String;)Ljava/lang/String;";
	private static final String THIS_CALL = "test/SubOps#instM(Ljava/lang/String;)Ljava/lang/String;";

	/** ① 编辑 A：新体调父类方法，旧侧"同名候选"其实没调它 ⇒ 必须选被调集合吻合的那个。 */
	@Test
	void uniqueStrictMaxWinsOverPositionalName() {
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(SUPER_REF),
		 List.of(Set.of(THIS_CALL), Set.of(SUPER_REF)));
		assertEquals(1, pick, "the candidate that actually calls the same target must win");
	}

	/** ② 镜像编辑 B：同一个旧候选集合，新体改成调本类方法 ⇒ 必须对称地选另一个。 */
	@Test
	void mirroredEditPicksTheOtherCandidate() {
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(THIS_CALL),
		 List.of(Set.of(THIS_CALL), Set.of(SUPER_REF)));
		assertEquals(0, pick, "the mirrored edit must resolve symmetrically, not be sacrificed");
	}

	/** ③ 平票：两个候选交集相等 ⇒ 不介入，交回既有规则（同名 → consumer → 原顺序）。 */
	@Test
	void tieDoesNotIntervene() {
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(SUPER_REF, THIS_CALL),
		 List.of(Set.of(SUPER_REF), Set.of(THIS_CALL)));
		assertEquals(-1, pick, "a tie must fall back to the existing rules instead of stealing a name");
	}

	/** ③b 全零交集：没有任何内容证据 ⇒ 不介入。 */
	@Test
	void emptyOverlapDoesNotIntervene() {
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(SUPER_REF),
		 List.of(Set.of(THIS_CALL), Set.of("test/Other#x()V")));
		assertEquals(-1, pick);
	}

	/** ③c 候选不足两个时没有"唯一"可言 ⇒ 不介入。 */
	@Test
	void singleCandidateDoesNotIntervene() {
		assertEquals(-1, LambdaAligner.calleeOverlapPickForTest(Set.of(SUPER_REF), List.of(Set.of(SUPER_REF))));
	}

	/** 样板调用不得进入证据集合：否则每个 lambda 都会因为装箱/空检查而"看起来相似"。 */
	@Test
	void boilerplateCalleesAreFilteredOut() {
		MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "lambda$f$0", "()V", null, null);
		InsnList insns = mn.instructions;
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Object", "<init>", "()V", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects",
		 "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics",
		 "checkNotNull", "(Ljava/lang/Object;)V", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer",
		 "valueOf", "(I)Ljava/lang/Integer;", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "test/BaseOps",
		 "baseM", "(Ljava/lang/String;)Ljava/lang/String;", false));

		Set<String> callees = LambdaAligner.calleesForTest(mn);

		assertTrue(callees.contains(SUPER_REF), "the business call must be kept as evidence: " + callees);
		assertEquals(1, callees.size(),
		 "boilerplate (Object.<init>, requireNonNull, Intrinsics, boxing) must not be evidence: " + callees);
	}

	/**
	 * 边界：{@code Object#toString} 过滤<b>不</b>做。
	 *
	 * <p>它是字符串拼接里最常见的一环，但"过滤 Object.*"会连带吞掉真实委派；当前口径是按需逐条
	 * 列举样板（{@code Object.<init>}、{@code requireNonNull*}、{@code Intrinsics*}、装箱拆箱），
	 * 其余一律保留为证据。这条断言就是把这个边界钉住：将来若要放宽，会先在这里变红。</p>
	 */
	@Test
	void objectToStringIsDeliberatelyNotFiltered() {
		MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "lambda$f$2", "()V", null, null);
		mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
		 "toString", "()Ljava/lang/String;", false));

		assertEquals(Set.of("java/lang/Object#toString()Ljava/lang/String;"),
		 LambdaAligner.calleesForTest(mn));
	}

	/** 合成名（lambda$ / access$）是"结构"不是"内容"，同样不进证据集合。 */
	@Test
	void syntheticCalleesAreFilteredOut() {
		MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "lambda$f$1", "()V", null, null);
		mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Self",
		 "lambda$f$0", "()V", false));
		mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Self",
		 "access$100", "()I", false));

		assertTrue(LambdaAligner.calleesForTest(mn).isEmpty(),
		 "synthetic names are structure, not content");
	}
	/**
	 * 边界（评审提出）：<b>单条共享工具调用</b>不得当作证据。
	 *
	 * <p>{@code Object#toString}、{@code String#valueOf} 这类调用在字符串拼接里极其常见，一条就能在
	 * {@code 1 vs 0} 时压过同名与 consumer 证据。因此单条交集必须来自非通用库 owner；两条以上才不论出处。</p>
	 */
	@Test
	void singleSharedUtilityCallIsNotEvidence() {
		String toString = "java/lang/Object#toString()Ljava/lang/String;";
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(toString),
		 List.of(Set.of(THIS_CALL), Set.of(toString)));
		assertEquals(-1, pick, "one shared JDK call must not override the same-name candidate");
	}

	/** 两条共享工具调用已经不太可能是巧合，允许作为证据。 */
	@Test
	void twoSharedUtilityCallsQualify() {
		String a = "java/lang/Object#toString()Ljava/lang/String;";
		String b = "java/lang/String#valueOf(I)Ljava/lang/String;";
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(a, b),
		 List.of(Set.of(THIS_CALL), Set.of(a, b)));
		assertEquals(1, pick, "two independent shared calls are strong enough to decide");
	}

	/** 单条但来自业务 owner（例如父类方法）仍然算证据：这正是 S41 的形态。 */
	@Test
	void singleBusinessCallQualifies() {
		int pick = LambdaAligner.calleeOverlapPickForTest(
		 Set.of(SUPER_REF),
		 List.of(Set.of(THIS_CALL), Set.of(SUPER_REF)));
		assertEquals(1, pick);
	}
}

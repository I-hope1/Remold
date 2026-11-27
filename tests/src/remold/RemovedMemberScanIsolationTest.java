package remold;

import org.junit.jupiter.api.Test;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.RemovedMember;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code [LAYOUT-READERS]} 的 {@code inconclusive} 必须按被删成员 owner 隔离。
 *
 * <p>现场（真机 E6-R2）：本批删除了 {@code InterfaceCycleTarget} 的字段/方法，同批另一个无关类
 * {@code DeepNestLocalTarget$2} 里有一条 {@code Supplier.get()} 的引用判不了。旧的扁平
 * {@code inconclusive} 让整批退回 {@code unscanned}，于是 {@code InterfaceCycleTarget} 被
 * {@code method(s) removed but the reader scan could not complete} 误杀。修复后只有触发该条
 * {@code inconclusive} 的 owner 保持 fail-closed，无关类照常按"无读者"放行。</p>
 */
class RemovedMemberScanIsolationTest {

	private static final String CLASS_A = "example.ClassA";
	private static final String CLASS_B = "example.ClassB";

	private static RemovedMember removedMethod(String owner, String name, String desc) {
		return new RemovedMember(owner, name, desc, false, false, false);
	}

	private static RemovedMember removedField(String owner, String name, String desc) {
		return new RemovedMember(owner, name, desc, true, false, false);
	}

	/** 预置 unscanned 结果（与 {@link RemovedMemberScanner#scan} 的初始形态一致）。 */
	private static Map<String, RemovedMemberScan> unscannedFor(
		Map<String, List<RemovedMember>> removedByClass) {
		Map<String, RemovedMemberScan> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<RemovedMember>> e : removedByClass.entrySet()) {
			List<RemovedMember> list = e.getValue();
			out.put(e.getKey(), RemovedMemberScan.unscanned(
				list.stream().anyMatch(m -> !m.isField()),
				list.stream().anyMatch(m -> m.isField() && m.isStatic()),
				List.of()));
		}
		return out;
	}

	@Test
	void anInconclusiveOwnerDoesNotPoisonAnUnrelatedClass() {
		Map<String, List<RemovedMember>> removedByClass = new LinkedHashMap<>();
		removedByClass.put(CLASS_A, List.of(removedMethod("example/ClassA", "traitVal", "()Ljava/lang/Object;")));
		removedByClass.put(CLASS_B, List.of(removedField("example/ClassB", "removedField", "I")));

		String inconclusive = "example/DeepNestLocalTarget$2 -> java/util/function/Supplier.get()Ljava/lang/Object; (post)";
		RemovedMemberReaders.Result result = new RemovedMemberReaders.Result(
			List.of(), List.of(inconclusive), List.of(),
			List.of(), List.of(), List.of(),
			Map.of("example/ClassA", List.of(inconclusive)), false);

		Map<String, RemovedMemberScan> out = unscannedFor(removedByClass);
		RemovedMemberScanner.applyScanResult(removedByClass, result, out);

		assertFalse(out.get(CLASS_A).scanned(),
			"ClassA's own reader scan is inconclusive, so it must stay fail-closed");
		assertTrue(out.get(CLASS_B).scanned(),
			"ClassB has no owner-attributed inconclusive and must be allowed to conclude");
		assertTrue(out.get(CLASS_B).readers().isEmpty(),
			"nothing reads ClassB.removedField after the change");

		LayoutDecision rejectA = LayoutDecisionEngine.decide(CLASS_A, List.of(), "reject", Liveness.LIVE,
			Set.of(CLASS_A), out.get(CLASS_A));
		assertEquals(LayoutAction.REJECT, rejectA.action(),
			"method removal has no liveness fallback: an incomplete scan must refuse");
		assertTrue(rejectA.reason() != null && rejectA.reason().contains("scan"),
			"the reason must still name the incomplete scan: " + rejectA.reason());

		LayoutDecision waiveB = LayoutDecisionEngine.decide(CLASS_B, List.of("- removedField"), "reject",
			Liveness.LIVE, Set.of(CLASS_B), out.get(CLASS_B));
		assertEquals(LayoutAction.WAIVE_NO_READERS, waiveB.action(),
			"ClassB's removal is conclusively unread, so it must no longer be killed by ClassA");
	}

	/**
	 * 对照：扫描器本身失败（读者字节码解析不了）是<b>全局</b>失败，不归属任何 owner，
	 * 因此所有类仍必须 fail-closed —— 隔离不能把真正的全局失败读成"没有读者"。
	 */
	@Test
	void aGlobalInconclusiveStillFailsClosedForEveryClass() {
		Map<String, List<RemovedMember>> removedByClass = new LinkedHashMap<>();
		removedByClass.put(CLASS_A, List.of(removedMethod("example/ClassA", "m", "()V")));
		removedByClass.put(CLASS_B, List.of(removedField("example/ClassB", "f", "I")));

		String global = "example/Broken (unparseable: truncated class file at 3)";
		RemovedMemberReaders.Result result = new RemovedMemberReaders.Result(
			List.of(), List.of(global), List.of(),
			List.of(), List.of(), List.of(), Map.of(), true);

		Map<String, RemovedMemberScan> out = unscannedFor(removedByClass);
		RemovedMemberScanner.applyScanResult(removedByClass, result, out);

		assertFalse(out.get(CLASS_A).scanned(), "global failure must fail closed for A");
		assertFalse(out.get(CLASS_B).scanned(), "global failure must fail closed for B");
		assertFalse(result.isConclusiveFor("example/ClassA"));
		assertFalse(result.isConclusiveFor("example/ClassB"));
	}

	/** 对照：B 自己确实有读者时仍然拒绝——隔离只去掉"被无关类毒死"，不放过真实读者。 */
	@Test
	void aRealReaderOfClassBStillRejectsIt() {
		Map<String, List<RemovedMember>> removedByClass = new LinkedHashMap<>();
		removedByClass.put(CLASS_A, List.of(removedMethod("example/ClassA", "traitVal", "()Ljava/lang/Object;")));
		removedByClass.put(CLASS_B, List.of(removedField("example/ClassB", "removedField", "I")));

		RemovedMember bMember = removedByClass.get(CLASS_B).get(0);
		RemovedMemberReaders.Hit hit = new RemovedMemberReaders.Hit(
			bMember, "example/RealReader", "example/ClassB", "field");
		String inconclusive = "example/Other -> java/util/function/Supplier.get()Ljava/lang/Object; (post)";
		RemovedMemberReaders.Result result = new RemovedMemberReaders.Result(
			List.of(hit), List.of(inconclusive), List.of(),
			List.of(), List.of(), List.of(),
			Map.of("example/ClassA", List.of(inconclusive)), false);

		Map<String, RemovedMemberScan> out = unscannedFor(removedByClass);
		RemovedMemberScanner.applyScanResult(removedByClass, result, out);

		assertTrue(out.get(CLASS_B).scanned());
		assertEquals(List.of("example.RealReader"), out.get(CLASS_B).readers(),
			"the real reader must be attributed to ClassB");

		LayoutDecision rejectB = LayoutDecisionEngine.decide(CLASS_B, List.of("- removedField"), "reject",
			Liveness.LIVE, Set.of(CLASS_B), out.get(CLASS_B));
		assertEquals(LayoutAction.REJECT, rejectB.action(),
			"a real out-of-batch reader must still refuse the removal");
	}
}

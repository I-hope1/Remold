import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR3 G1 双哈希分支的夹具：{@code alignOutputHash != jvmEffHash} 时，G1 仍然成立。
 *
 * <p><b>为什么需要这个夹具</b>：真实路径里 {@code align()} 的产物并不是最后进 JVM 的字节——
 * {@code InitFix} 打补丁、织入等 transformer 会在对齐之后再改一次，于是
 * {@code AlignmentMetaStore.commit(preAlign, alignOutput, jvmEffective)} 的
 * {@code alignOutput} 与 {@code jvmEffective} <b>可以不同</b>。规格
 * （{@code docs/vcs/03-live-raw-to-raw-alignment.md} §3.3 / §5）把 G1 定义为
 * "SHA-256({@code E_prev_used}) 与 {@code jvmEffHash} <b>或</b> {@code alignOutputHash} 任一相等"，
 * 但既有夹具（{@link Pr3RawAlignmentScenarioTest} / {@link AlignmentMetaEquivalenceTest}）
 * 一律 {@code commit(..., alignOutput, alignOutput)}，两个哈希恒等，因此"只经 jvmEffHash 命中"
 * 这条分支从来没有用例走过（PR #12 把它登记为建模缺口）。
 *
 * <p><b>夹具怎么造出两份不同的字节</b>：第一轮 {@code align()} 的产物记作 {@code A1}，
 * 再用 ASM 在 {@code A1} 上做一次<b>确定性后置改写</b>（追加一个不参与对齐的静态方法，
 * 模拟 {@code InitFix} 注入的补丁方法体）得到 {@code J1}。{@code A1} 与 {@code J1} 内容不同、
 * 方法表也不同，但 lambda 槽位表一致——正好是生产里的形态。</p>
 *
 * <p><b>三条用例各自锁定一段因果</b>：</p>
 * <ol>
 *   <li>{@link #jvmEffectiveHashAloneAdmitsTheGeneration}：旧侧 = {@code J1}，只有
 *       {@code jvmEffHash} 命中 → 必须 {@code runRGate=PASSED} / {@code baselineTree=RAW_PREV}，
 *       且终局活体名到方法体的绑定正确（证明 G1 过了之后翻译表真的被用上）；</li>
 *   <li>{@link #alignOutputHashAloneAlsoAdmitsTheGeneration}：旧侧 = {@code J1}、但它只等于
 *       这一代的 {@code alignOutput}（{@code jvmEffHash} 是第三份字节）→ 同样必须 {@code PASSED}。
 *       两条一起才证明"两路哈希都真的被采信"，而不是其中一路是死代码；</li>
 *   <li>{@link #staleGenerationIsRejectedInsteadOfRebound}：两份哈希都不等于旧侧 → 不许采信，
 *       整类退回 Run E（{@code baselineTree=EFF_FALLBACK}、{@code metaGeneration=-1}），
 *       且 fail-closed 不等于乱绑：活体名照样要绑对。</li>
 * </ol>
 *
 * <p><b>变异锚点</b>：把 {@code AlignmentMetaStore.headMatching} 里 {@code jvmEffHash} 那一路
 * 去掉 → 用例 1 必红；把 {@code alignOutputHash} 那一路去掉 → 用例 2 必红；两路都去掉 →
 * 用例 1/2 都红，而用例 3 保持绿（它要的就是"都不命中"）。</p>
 */
public class Pr3JvmEffHashGateTest {

	private static final String CLASS = "test/Pr3GateCase";

	/** 后置改写的两档"标签"：同一个 A1 改写出的两份字节必须互不相同。 */
	private static final int TAG_JVM_EFFECTIVE = 7;
	private static final int TAG_ONLY_ALIGN_OUTPUT = 11;
	private static final int TAG_STALE_A = 21;
	private static final int TAG_STALE_B = 42;

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.META_SOURCE = false;
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		AlignmentMetaStore.INSTANCE.reset();
	}

	// ------------------------------------------------------------------------------------------------
	// 1. 只命中 jvmEffHash：alignOutput 与 jvmEffective 是两份不同的字节
	// ------------------------------------------------------------------------------------------------

	@Test
	void jvmEffectiveHashAloneAdmitsTheGeneration(@TempDir Path tmp) throws Exception {
		Chain chain = firstRound(tmp, "g1");

		// 后置改写：align() 之后 JVM 还会再跑一次 transformer（InitFix 打补丁 / 织入）。
		byte[] jvmEffective = postTransform(chain.alignOutput, TAG_JVM_EFFECTIVE);
		assertFalse(Arrays.equals(chain.alignOutput, jvmEffective),
			"premise: the post-transform must really change the bytes, otherwise this fixture is a no-op");
		assertNotEquals(AlignmentMetaStore.sha256Hex(chain.alignOutput),
			AlignmentMetaStore.sha256Hex(jvmEffective),
			"premise: the two G1 hashes must differ");

		commit(chain.loader, chain.raw2, chain.alignOutput, jvmEffective, chain.result);

		AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(chain.loader, CLASS);
		assertNotNull(head, "premise: round 1 must leave a head entry for the class");
		assertEquals(AlignmentMetaStore.sha256Hex(chain.raw2), head.preAlignSha256,
			"premise: preAlign must fingerprint R_new of round 1");
		assertEquals(AlignmentMetaStore.sha256Hex(chain.alignOutput), head.alignOutputSha256,
			"premise: alignOutputHash must fingerprint the align() output");
		assertEquals(AlignmentMetaStore.sha256Hex(jvmEffective), head.jvmEffSha256,
			"premise: jvmEffHash must fingerprint the bytes handed to redefineClasses");
		assertNotEquals(head.alignOutputSha256, head.jvmEffSha256,
			"premise: this fixture exists because the two recorded hashes differ");

		// 第二轮：调用方拿"实际进 JVM 的字节"当旧侧 —— 只可能命中 jvmEffHash 那一路。
		byte[] eff3 = align(jvmEffective, chain.raw3, null, chain.loader);
		LambdaAligner.AlignmentResult round2 = LambdaAligner.LAST_RESULT;
		assertNotNull(round2, "round 2 must publish an alignment result");
		assertEquals(1, round2.metaGeneration,
			"round 2 must read the recorded generation through jvmEffHash, got metaGeneration="
				+ round2.metaGeneration);
		assertEquals(LambdaAligner.RunRGate.PASSED, round2.runRGate,
			"G1 must accept the generation when only jvmEffHash matches (alignOutput is a different "
				+ "transform stage), got runRGate=" + round2.runRGate);
		assertEquals(LambdaAligner.BaselineTree.RAW_PREV, round2.baselineTree,
			"an accepted generation must pair against R_prev, not fall back to Run E, got "
				+ round2.baselineTree);
		assertEquals(0, round2.positionalFallbacks,
			"Run R pairs from evidence only, got positionalFallbacks=" + round2.positionalFallbacks);

		// 终局：活体名 -> 方法体绑定正确（证明通过 G1 之后翻译表真的被用上）。
		Bytecode out = new Bytecode(eff3);
		assertEquals("a", out.bodyOf("lambda$run$0"),
			"live name lambda$run$0 must still carry the a-body, got " + out);
		assertEquals("bMod", out.bodyOf("lambda$run$1"),
			"live name lambda$run$1 must carry the edited b-body, got " + out);
		assertEquals("x", out.bodyOf(chain.inventedX),
			"the invented slot must still carry the x-body, got " + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 2. 只命中 alignOutputHash：旧侧等于这一代的对齐产出，jvmEffHash 是第三份字节
	// ------------------------------------------------------------------------------------------------

	@Test
	void alignOutputHashAloneAlsoAdmitsTheGeneration(@TempDir Path tmp) throws Exception {
		Chain chain = firstRound(tmp, "g2");

		// 这一代记录的三份字节：preAlign=R1、alignOutput=A1（= 本轮调用方的旧侧）、
		// jvmEffective=K1（第三份，与旧侧不同）。这些字节由调用方按代记录，
		// headMatching 的口径就是"任一路命中即认这一代"。
		byte[] staleJvmEffective = postTransform(chain.alignOutput, TAG_ONLY_ALIGN_OUTPUT);
		assertNotEquals(AlignmentMetaStore.sha256Hex(chain.alignOutput),
			AlignmentMetaStore.sha256Hex(staleJvmEffective),
			"premise: the recorded jvmEffective must differ from the alignOutput used as the old side");

		commit(chain.loader, chain.raw2, chain.alignOutput, staleJvmEffective, chain.result);

		byte[] eff3 = align(chain.alignOutput, chain.raw3, null, chain.loader);
		LambdaAligner.AlignmentResult round2 = LambdaAligner.LAST_RESULT;
		assertNotNull(round2, "round 2 must publish an alignment result");
		assertEquals(1, round2.metaGeneration,
			"round 2 must read the recorded generation through alignOutputHash, got metaGeneration="
				+ round2.metaGeneration);
		assertEquals(LambdaAligner.RunRGate.PASSED, round2.runRGate,
			"G1 must accept the generation when only alignOutputHash matches (the caller normalized the "
				+ "old side to the alignment output), got runRGate=" + round2.runRGate);
		assertEquals(LambdaAligner.BaselineTree.RAW_PREV, round2.baselineTree,
			"an accepted generation must pair against R_prev, got " + round2.baselineTree);

		Bytecode out = new Bytecode(eff3);
		assertEquals("a", out.bodyOf("lambda$run$0"), "live name lambda$run$0 must carry the a-body, got " + out);
		assertEquals("bMod", out.bodyOf("lambda$run$1"), "live name lambda$run$1 must carry the bMod-body, got " + out);
		assertEquals("x", out.bodyOf(chain.inventedX), "the invented slot must carry the x-body, got " + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 3. 对照：两路哈希都不等于旧侧 -> 不许采信，整类退回 Run E，且 fail-closed 不误绑
	// ------------------------------------------------------------------------------------------------

	@Test
	void staleGenerationIsRejectedInsteadOfRebound(@TempDir Path tmp) throws Exception {
		Chain chain = firstRound(tmp, "g3");

		// 两份"第三份字节"：既不等于旧侧（A1），也不等于 A1 之外的任何东西。
		byte[] staleAlignOutput = postTransform(chain.alignOutput, TAG_STALE_A);
		byte[] staleJvmEffective = postTransform(chain.alignOutput, TAG_STALE_B);
		commit(chain.loader, chain.raw2, staleAlignOutput, staleJvmEffective, chain.result);

		// 调用方回灌上一轮自造名（旧契约）：Run E 下这是自造名的唯一来源。
		byte[] eff3 = align(chain.alignOutput, chain.raw3, chain.result.inventedNames, chain.loader);
		LambdaAligner.AlignmentResult round2 = LambdaAligner.LAST_RESULT;
		assertNotNull(round2, "round 2 must publish an alignment result");

		// 实测读数：两路哈希都不命中时，本实现报的是 NO_ENTRY 而不是 RAW_PREV_MISMATCH。
		// 原因是同一条判据出现在两处——headMatching 内部已经用 SHA-256 精判过（任一路相等才返回条目），
		// 闸门里的 RAW_PREV_MISMATCH 分支只可能在"条目能取到、但闸门复核不过"时命中（G2 的 blob
		// 指纹不符 / 类名不符）；对 align() 这条路，"代际身份不成立"就是 NO_ENTRY。
		System.out.println("[diag] stale-generation G1 gate = " + round2.runRGate
			+ ", baselineTree = " + round2.baselineTree);
		assertEquals(LambdaAligner.RunRGate.NO_ENTRY, round2.runRGate,
			"a generation whose two G1 hashes both miss the old side must not be attributed to the "
				+ "round (Spec §6: no entry / G1 mismatch -> Run E), got runRGate=" + round2.runRGate);
		assertEquals(-1, round2.metaGeneration,
			"no generation may be attributed to this round, got metaGeneration=" + round2.metaGeneration);
		assertEquals(LambdaAligner.BaselineTree.EFF_FALLBACK, round2.baselineTree,
			"a rejected generation must fall back to the effective-bytes baseline, got "
				+ round2.baselineTree);

		// fail-closed 不等于乱绑：兼容跑道上活体名同样必须守住（这条夹具两跑道结果一致）。
		Bytecode out = new Bytecode(eff3);
		assertEquals("a", out.bodyOf("lambda$run$0"), "fallback must keep $0 bound to the a-body, got " + out);
		assertEquals("bMod", out.bodyOf("lambda$run$1"), "fallback must keep $1 bound to the bMod-body, got " + out);
		assertEquals("x", out.bodyOf(chain.inventedX), "fallback must keep the invented slot bound to x, got " + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 夹具与工具
	// ------------------------------------------------------------------------------------------------

	private static final String V1 =
		"        list.add(() -> a());\n"
		+ "        list.add(() -> b());";

	private static final String V2 =
		"        list.add(() -> x());\n"
		+ "        list.add(() -> a());\n"
		+ "        list.add(() -> b());";

	/** 微调轮：b 改体（同一源码槽位原地更新），用来验证"过了 G1 之后翻译表真的被用上"。 */
	private static final String V3_TWEAK =
		"        list.add(() -> x());\n"
		+ "        list.add(() -> a());\n"
		+ "        list.add(() -> bMod());";

	/** 第一轮的真实产物：raw1 -> raw2 的 align 结果 + 落库所需的一切读数。 */
	private static final class Chain {
		final ClassLoader loader;
		final byte[] raw2;
		final byte[] raw3;
		final byte[] alignOutput;
		final LambdaAligner.AlignmentResult result;
		final String inventedX;

		Chain(ClassLoader loader, byte[] raw2, byte[] raw3, byte[] alignOutput,
		      LambdaAligner.AlignmentResult result, String inventedX) {
			this.loader = loader;
			this.raw2 = raw2;
			this.raw3 = raw3;
			this.alignOutput = alignOutput;
			this.result = result;
			this.inventedX = inventedX;
		}
	}

	private static Chain firstRound(Path tmp, String dir) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, dir + "-v1", V1);
		byte[] raw2 = compile(javac, tmp, dir + "-v2", V2);
		byte[] raw3 = compile(javac, tmp, dir + "-v3", V3_TWEAK);

		// 前置基线：v2 的 javac 槽位身份（夹具漂移会让后面的断言空转）。
		Bytecode v2 = new Bytecode(raw2);
		assertEquals("x", v2.bodyOf("lambda$run$0"), "fixture premise: v2 raw lambda$run$0 is the x-lambda");
		assertEquals("a", v2.bodyOf("lambda$run$1"), "fixture premise: v2 raw lambda$run$1 is the a-lambda");
		assertEquals("b", v2.bodyOf("lambda$run$2"), "fixture premise: v2 raw lambda$run$2 is the b-lambda");

		LambdaAligner.META_SOURCE = true;
		ClassLoader loader = new ClassLoader() { };
		LambdaAligner.LAST_RESULT = null;
		byte[] alignOutput = LambdaAligner.align(raw1, raw2, null, null, null, loader);
		LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
		assertNotNull(r, "round 1 must publish an alignment result");
		assertFalse(r.inventedNames.isEmpty(),
			"fixture premise: round 1 must invent a name for the front-inserted lambda");
		assertFalse(r.rawToEffKeys.isEmpty(),
			"fixture premise: round 1 must publish a Raw->Eff translation table (Run R prerequisite)");
		assertTrue(AlignmentMetaStore.INSTANCE.blobBytes() == 0,
			"premise: nothing may be recorded before the redefine is known to be effective");
		return new Chain(loader, raw2, raw3, alignOutput, r, onlyInvented(r.inventedNames));
	}

	/** 显式翻译表落库（生产路径由 {@code RemoldAgent} 传值，不依赖 align-后-立即-commit 的时序）。 */
	private static void commit(ClassLoader loader, byte[] preAlign, byte[] alignOutput, byte[] jvmEffective,
	                           LambdaAligner.AlignmentResult r) {
		assertNotNull(r, "commit needs the alignment result of this generation");
		AlignmentMetaStore.INSTANCE.commit(loader, CLASS, preAlign, alignOutput, jvmEffective,
			r.inventedNames, LambdaAligner.scanGhostOriginHashes(jvmEffective),
			r.methodMapping == null ? Map.of() : r.methodMapping, r.bridgeCount, r.rawToEffKeys);
	}

	private static byte[] align(byte[] oldBytes, byte[] newBytes, Set<String> invented, ClassLoader loader) {
		LambdaAligner.LAST_RESULT = null;
		return LambdaAligner.align(oldBytes, newBytes, null, null,
			invented == null ? Set.of() : invented, loader);
	}

	/**
	 * 确定性后置改写：在 align() 产物上追加一个不参与对齐的静态方法。
	 *
	 * <p>模拟"对齐之后 JVM 还会再跑一次 transformer"这一事实（{@code InitFix} 注入补丁方法）。
	 * 方法名既不是 {@code lambda$} 系也不是 {@code access$} 系，因此不会被对齐器当成槽位；
	 * 追加方法只改方法表尾部，不改任何既有 lambda 的字节。</p>
	 */
	private static byte[] postTransform(byte[] aligned, int tag) {
		ClassNode cn = new ClassNode();
		new ClassReader(aligned).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			assertNotEquals("$remoldPatchTag", mn.name,
				"premise: the injected method name must be free, otherwise the post-transform is ambiguous");
		}
		MethodNode injected = new MethodNode(Opcodes.ASM9,
			Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "$remoldPatchTag", "()I", null, null);
		injected.instructions.add(new LdcInsnNode(tag));
		injected.instructions.add(new InsnNode(Opcodes.IRETURN));
		injected.maxStack = 1;
		injected.maxLocals = 0;
		cn.methods.add(injected);
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private static String onlyInvented(Set<String> invented) {
		assertEquals(1, invented.size(), "fixture premise: exactly one invented name, got " + invented);
		return invented.iterator().next();
	}

	private static byte[] compile(String javac, Path tmp, String dir, String body) throws Exception {
		String src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class Pr3GateCase {\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    static void bMod() { System.out.println(\"bMod\"); }\n"
			+ "    static void x() { System.out.println(\"x\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ body + "\n"
			+ "    }\n"
			+ "}\n";
		Path srcDir = Files.createDirectories(tmp.resolve("src-" + dir));
		Path outDir = Files.createDirectories(tmp.resolve("out-" + dir));
		Path f = srcDir.resolve("Pr3GateCase.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		// -g：带上 LocalVariableTable，让捕获分析拿到形参名（对齐器的强证据）。
		HstestFixtures.compile(javac, outDir, List.of("-g"), f.toFile());
		return Files.readAllBytes(outDir.resolve("test/Pr3GateCase.class"));
	}

	/** 产物字节码的可读视图：lambda 名字 -> 方法体里那条业务调用的名字。 */
	private static final class Bytecode {
		private final ClassNode cn;

		Bytecode(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		/** 该名字对应方法体"承载的语义"：体内那条指向本类业务方法的调用。 */
		String bodyOf(String name) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.equals(name)) continue;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLASS)) return mi.name;
				}
				return null;
			}
			return null;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				view.put(mn.name + mn.desc, bodyOf(mn.name));
			}
			return view.toString();
		}
	}
}

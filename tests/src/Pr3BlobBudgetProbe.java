import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * PR3 blob 预算降级的独立 JVM 探针（由 {@code Pr3RawAlignmentScenarioTest} fork 调用）。
 *
 * <p><b>为什么必须另起进程</b>：{@code AlignmentMetaStore.BLOB_BUDGET_BYTES} 是类初始化时定值的
 * {@code static final}（{@code -Dremold.vcs.meta_blob_bytes}），同一个 JVM 里改不动。这里用
 * {@code -Dremold.vcs.meta_blob_bytes=16} 启动，把预算压到任何一份 class 都放不下。</p>
 *
 * <p><b>钉住的契约</b>：预算不足时"丢 blob 保哈希"——本代元数据照记（哈希/自造名/翻译表都在），
 * 但 {@code R_prev} 字节没存下；下一轮该类必须<b>整类</b>退回兼容跑道
 * （{@code baselineTree=EFF_FALLBACK} / {@code runRGate=NO_RAW_PREV}），
 * {@code droppedBlobCount()>0} 给出可观测读数，而不是静默地拿错基线配对。
 * 同时产物的活体名/方法体仍然正确（fail-closed 不等于乱绑）。</p>
 */
public final class Pr3BlobBudgetProbe {

	private static final String CLASS = "test/Pr3Case";

	private Pr3BlobBudgetProbe() { }

	public static void main(String[] args) throws Exception {
		try {
			run();
			System.out.println("PR3-BLOB-DEGRADE OK");
		} catch (Throwable t) {
			System.out.println("PR3-BLOB-DEGRADE FAILED: " + t);
			t.printStackTrace(System.out);
			System.exit(1);
		}
	}

	private static void run() throws Exception {
		Path tmp = Files.createTempDirectory("pr3-blob-budget");
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "b1",
			"        list.add(() -> a());\n        list.add(() -> b());");
		byte[] raw2 = compile(javac, tmp, "b2",
			"        list.add(() -> x());\n        list.add(() -> a());\n        list.add(() -> b());");
		byte[] raw3 = compile(javac, tmp, "b3",
			"        list.add(() -> x());\n        list.add(() -> a());\n        list.add(() -> bMod());");

		LambdaAligner.META_SOURCE = true;
		ClassLoader loader = new ClassLoader() { };

		LambdaAligner.LAST_RESULT = null;
		byte[] eff2 = LambdaAligner.align(raw1, raw2, null, null, null, loader);
		LambdaAligner.AlignmentResult r1 = require(LambdaAligner.LAST_RESULT, "round 1 must publish a result");
		AlignmentMetaStore.INSTANCE.commit(loader, CLASS, raw2, eff2, eff2,
			r1.inventedNames, LambdaAligner.scanGhostOriginHashes(eff2),
			r1.methodMapping == null ? Map.of() : r1.methodMapping, r1.bridgeCount);

		// 预算只有 16B：任何一代都存不下 blob，必须记账为"丢弃"。
		int dropped = AlignmentMetaStore.INSTANCE.droppedBlobCount();
		long held = AlignmentMetaStore.INSTANCE.blobBytes();
		check(dropped > 0, "droppedBlobCount must be > 0 under a 16B budget, got " + dropped);
		check(held == 0, "no R_prev blob may be retained under a 16B budget, got " + held + "B");
		check(AlignmentMetaStore.INSTANCE.head(loader, CLASS) != null,
			"the generation entry itself must still be recorded (drop blob, keep hashes)");

		String inventedX = only(r1.inventedNames);
		Bytecode eff = new Bytecode(eff2);
		check("x".equals(eff.bodyOf(inventedX)), "fixture premise: invented name carries x, got " + eff);

		// 第二轮：blob 缺失 → 整类 fail-closed 退回兼容跑道。
		LambdaAligner.LAST_RESULT = null;
		byte[] eff3 = LambdaAligner.align(eff2, raw3, null, null, null, loader);
		LambdaAligner.AlignmentResult r2 = require(LambdaAligner.LAST_RESULT, "round 2 must publish a result");

		check(r2.baselineTree == LambdaAligner.BaselineTree.EFF_FALLBACK,
			"round 2 must fall back to the effective baseline, got " + r2.baselineTree);
		check(r2.runRGate == LambdaAligner.RunRGate.NO_RAW_PREV,
			"the gate must report NO_RAW_PREV, got " + r2.runRGate);

		Bytecode out = new Bytecode(eff3);
		check("a".equals(out.bodyOf("lambda$run$0")), "fallback must keep $0 -> a-body, got " + out);
		check("bMod".equals(out.bodyOf("lambda$run$1")), "fallback must keep $1 -> bMod-body, got " + out);
		check("x".equals(out.bodyOf(inventedX)), "fallback must keep the invented slot -> x-body, got " + out);

		System.out.println("PR3-BLOB-DEGRADE baseline=" + r2.baselineTree + " gate=" + r2.runRGate
			+ " dropped=" + dropped + " held=" + held + "B out=" + out);
	}

	private static byte[] compile(String javac, Path tmp, String dir, String body) throws Exception {
		String src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class Pr3Case {\n"
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
		Path f = srcDir.resolve("Pr3Case.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		HstestFixtures.compile(javac, outDir, f.toFile());
		return Files.readAllBytes(outDir.resolve("test/Pr3Case.class"));
	}

	private static <T> T require(T value, String message) {
		if (value == null) throw new IllegalStateException(message);
		return value;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	private static String only(Set<String> names) {
		check(names.size() == 1, "fixture premise: exactly one invented name, got " + names);
		return names.iterator().next();
	}

	/** 产物视图：lambda 名字 -> 方法体委托的业务方法名（幽灵桩为 null）。 */
	private static final class Bytecode {
		private final ClassNode cn;

		Bytecode(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		String bodyOf(String name) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.equals(name)) continue;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLASS)) return mi.name;
				}
			}
			return null;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (mn.name.startsWith("lambda$")) view.put(mn.name, bodyOf(mn.name));
			}
			return view.toString();
		}
	}
}

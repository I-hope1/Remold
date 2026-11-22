package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guards for the two PR #59 fallback heuristics on the <b>real pairing path</b>
 * ({@link LambdaAligner#align(byte[], byte[])} driven by javac bytecode pairs, not by the
 * package-private decision helpers).
 *
 * <p><b>Why a separate class</b>: {@code PutAnchorFallbackTest} only calls the pure helpers
 * ({@code uniquePutAnchorForTest} / {@code disjointSameClassHelpersForTest} ...). Removing the
 * heuristic from its production call site therefore stayed green there. These fixtures lock the
 * two call sites that actually decide at runtime:</p>
 *
 * <ol>
 *   <li><b>unique {@code PUT:} field anchor</b> ({@code uniquePutAnchorPick} in {@code step2PassA},
 *       and {@code putAnchorConflict} inside {@code fallbackPairingAllowed}). A named-field write
 *       that occurs exactly once in both host sequences identifies the old lambda by <b>field
 *       identity</b>; positional same-name evidence must not override it, and two unique anchors
 *       pointing at <i>different</i> fields must never be paired.</li>
 *   <li><b>disjoint own-class helper veto</b> ({@code disjointSameClassHelpers} inside
 *       {@code fallbackPairingAllowed}). A <i>renamed</i> fallback pair whose own-class
 *       private/instance helper sets are both non-empty and disjoint is order evidence without
 *       content evidence, so it must be refused: the old name is ghosted (loud fuse) instead of
 *       silently running the new body.</li>
 * </ol>
 *
 * <p><b>Anti-vacuity controls</b> (each fixture carries its own):</p>
 * <ul>
 *   <li><b>去触发（变异）</b>：把生产调用点改坏（{@code if (false)} / {@code anchorPick = null}）
 *       后本类必须变红；这正是本类存在的目的。</li>
 *   <li><b>换无关输入</b>：把字段写换成方法实参（{@code execute(...)}）后，字段锚点不成立，
 *       配对退回既有证据 —— {@link #callConsumedSwapFallsBackToLegacyEvidence()} 断言产物
 *       按同名规则落位，证明上面的"按字段配对"不是产物里恒真的性质。</li>
 *   <li>disjoint 否决自带合法对照：同一个 fixture 里"换名 + 共享 helper"的一对仍必须正常配对
 *       （{@link #disjointOwnHelpersVetoRenamedPairButKeepSharedHelperLane()} 的 control 断言），
 *       证明该否决不是"凡换名一律拒绝"。</li>
 * </ul>
 *
 * <p>断言口径落在产物字节码终局：old CallSite 认的名字（{@code lambda$<host>$N}）在产物里承载
 * 哪个方法体、每个字段写位点的 indy 指向哪个名字。不用日志/计数器代理。</p>
 */
class PutAnchorPairingTest {

	// ------------------------------------------------------------------------------------------
	// case A1: unique PUT anchor must outrank same-name evidence (step2PassA)
	//
	// old:  $0 = this.fieldB = () -> onFieldB();   $1 = execute(() -> viaExecute());
	// new:  $0 = execute(() -> viaExecute2());     $1 = this.fieldB = () -> onFieldB2();
	//
	// Both bodies change structurally (different callees), so Step 1 (hash) and the skeleton pass
	// cannot pair them, and the swap makes the javac slots disagree with the field identity:
	//   - with the anchor   : fieldB's write site keeps old name $0  (correct, field identity kept)
	//   - without the anchor: same-name evidence pairs new $1 with old $1 (the execute caller),
	//                         i.e. old $0 now runs the execute lambda and old $1 writes fieldB.
	// ------------------------------------------------------------------------------------------
	private static final String A1_OLD = """
		package remold.test;
		public class PutAnchorHost {
		    private Runnable fieldB;
		    static void onFieldB() { System.out.println("fieldB v1"); }
		    static void viaExecute() { System.out.println("execute v1"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        this.fieldB = () -> onFieldB();
		        execute(() -> viaExecute());
		    }
		}
		""";

	private static final String A1_NEW = """
		package remold.test;
		public class PutAnchorHost {
		    private Runnable fieldB;
		    static void onFieldB2() { System.out.println("fieldB v2"); }
		    static void viaExecute2() { System.out.println("execute v2"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        execute(() -> viaExecute2());
		        this.fieldB = () -> onFieldB2();
		    }
		}
		""";

	/** 换无关输入对照：同样的源码形态，但两个 lambda 都交给 {@code execute(...)}，没有字段写。 */
	private static final String A1_CALL_OLD = """
		package remold.test;
		public class CallConsumedHost {
		    static void onFieldB() { System.out.println("fieldB v1"); }
		    static void viaExecute() { System.out.println("execute v1"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        execute(() -> onFieldB());
		        execute(() -> viaExecute());
		    }
		}
		""";

	private static final String A1_CALL_NEW = """
		package remold.test;
		public class CallConsumedHost {
		    static void onFieldB2() { System.out.println("fieldB v2"); }
		    static void viaExecute2() { System.out.println("execute v2"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        execute(() -> viaExecute2());
		        execute(() -> onFieldB2());
		    }
		}
		""";

	// ------------------------------------------------------------------------------------------
	// case A2: two unique PUT anchors pointing at different fields must never be paired
	//
	// old: $0 = this.fieldA = () -> onFieldA();
	// new: $0 = this.fieldB = () -> onFieldB();
	//
	// There is no old lambda writing fieldB, so no anchor partner exists either: the field identity
	// contradicts itself. The pair must be refused (old name ghosted / old CallSite fuses loudly)
	// instead of being accepted by same-name evidence.
	// ------------------------------------------------------------------------------------------
	private static final String A2_OLD = """
		package remold.test;
		public class FieldIdentityHost {
		    private Runnable fieldA;
		    private Runnable fieldB;
		    static void onFieldA() { System.out.println("fieldA"); }
		    static void onFieldB() { System.out.println("fieldB"); }
		    public void run() {
		        this.fieldA = () -> onFieldA();
		    }
		}
		""";

	private static final String A2_NEW = """
		package remold.test;
		public class FieldIdentityHost {
		    private Runnable fieldA;
		    private Runnable fieldB;
		    static void onFieldA() { System.out.println("fieldA"); }
		    static void onFieldB() { System.out.println("fieldB"); }
		    public void run() {
		        this.fieldB = () -> onFieldB();
		    }
		}
		""";

	// ------------------------------------------------------------------------------------------
	// case B: disjoint own-class helper veto (renamed fallback) + shared-helper control lane
	//
	// old:  $0 = execute(() -> helperB());      $1 = execute(() -> audit("v1"));
	// new:  $0 = () -> "t"  (Supplier, other descriptor group, only shifts the numbering)
	//       $1 = execute(() -> helperC());      $2 = execute(() -> audit("v2"));
	//
	// $1 (helperC) vs $0 (helperB): renamed, both helpers private static in the host class, the two
	// sets are disjoint  => veto: $0 must be ghosted, the helperC body must get a fresh name.
	// $2 (audit "v2") vs $1 (audit "v1"): renamed but sharing the `audit` helper => still paired
	//   (control: the veto is not "refuse every rename").
	// Zero-capture (static) lambdas are used on purpose so no capture bridge can re-point the
	// ghosted old name at the new body.
	// ------------------------------------------------------------------------------------------
	private static final String B_OLD = """
		package remold.test;
		public class DisjointHelperHost {
		    private static void helperB() { System.out.println("B"); }
		    private static void helperC() { System.out.println("C"); }
		    private static void audit(String s) { System.out.println(s); }
		    static void execute(Runnable r) { r.run(); }
		    public static void run() {
		        execute(() -> helperB());
		        execute(() -> audit("v1"));
		    }
		}
		""";

	private static final String B_NEW = """
		package remold.test;
		import java.util.function.Supplier;
		public class DisjointHelperHost {
		    private static void helperB() { System.out.println("B"); }
		    private static void helperC() { System.out.println("C"); }
		    private static void audit(String s) { System.out.println(s); }
		    static void execute(Runnable r) { r.run(); }
		    public static void run() {
		        Supplier<String> tag = () -> "t";
		        execute(() -> helperC());
		        execute(() -> audit("v2"));
		    }
		}
		""";

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.DEBUG = false;
		LambdaAligner.META_SOURCE = false;
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
	}

	@Test
	@DisplayName("L21 step2PassA：唯一 PUT 字段锚点压过同名证据 —— fieldB 写位点保住旧名字 $0")
	void uniquePutAnchorOutranksSameNameInPassA() {
		byte[] oldBytes = compile("PutAnchorHost", A1_OLD);
		byte[] newBytes = compile("PutAnchorHost", A1_NEW);
		View raw = new View(newBytes);

		// 前置基线：新版 javac 槽位与字段身份已经错位（$0 是 execute 调用、$1 才是 fieldB 写）。
		assertEquals("viaExecute2", raw.calleeOf("lambda$run$0()V"), "fixture premise: new $0 is the execute lambda, got " + raw);
		assertEquals("onFieldB2", raw.calleeOf("lambda$run$1()V"), "fixture premise: new $1 is the fieldB lambda, got " + raw);
		assertEquals("onFieldB", new View(oldBytes).calleeOf("lambda$run$0()V"),
			"fixture premise: old $0 is the fieldB lambda");

		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		View out = new View(aligned);

		// 终局一：fieldB 的写位点在产物里仍然指向旧 CallSite 认的那个名字（$0）。
		assertEquals("lambda$run$0", out.putTargets().get("fieldB"),
			"the fieldB write site must keep binding old name lambda$run$0, got " + out);
		// 终局二：那个名字承载的确实是 fieldB 的 lambda 体（不是 execute 调用体）。
		assertEquals("onFieldB2", out.calleeOf("lambda$run$0()V"),
			"old name lambda$run$0 must carry the fieldB body, got " + out);
		assertEquals("viaExecute2", out.calleeOf("lambda$run$1()V"),
			"old name lambda$run$1 must carry the execute body, got " + out);
		assertTrue(LambdaAligner.LAST_RESULT.stats.passAPairs >= 1,
			"fixture premise: the anchor must decide in Pass A, got " + LambdaAligner.LAST_RESULT.stats);
	}

	@Test
	@DisplayName("L21 对照（换无关输入）：改成方法实参后字段锚点不成立，配对退回同名证据")
	void callConsumedSwapFallsBackToLegacyEvidence() {
		byte[] oldBytes = compile("CallConsumedHost", A1_CALL_OLD);
		byte[] newBytes = compile("CallConsumedHost", A1_CALL_NEW);

		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		View out = new View(aligned);

		// 没有 PUT 消费端 => 没有字段身份证据，只有同名；因此产物里 $0 仍是 execute 体、
		// $1 仍是查表体（与 case A1 的"按字段重新落位"相反）。这就是对照：断言不是恒真。
		assertEquals("viaExecute2", out.calleeOf("lambda$run$0()V"),
			"without a PUT consumer the same-name fallback keeps $0 for the execute body, got " + out);
		assertTrue(out.putTargets().isEmpty(), "control fixture must not write any field, got " + out);
	}

	@Test
	@DisplayName("L21 putAnchorConflict：两个唯一锚点指向不同字段且无对应物 —— 拒绝配对，旧名走 ghost")
	void fieldIdentityContradictionRefusesPairing() {
		byte[] oldBytes = compile("FieldIdentityHost", A2_OLD);
		byte[] newBytes = compile("FieldIdentityHost", A2_NEW);

		// 前置基线：两侧都是唯一 PUT，但字段不同（fieldA vs fieldB），且旧类没有 fieldB 的 lambda。
		assertEquals("lambda$run$0", new View(oldBytes).putTargets().get("fieldA"),
			"fixture premise: the old fieldA write consumes lambda$run$0");
		assertEquals("lambda$run$0",
			new View(newBytes).putTargets().get("fieldB"),
			"fixture premise: new fieldB write consumes lambda$run$0");

		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		View out = new View(aligned);

		assertTrue(out.hasMethod("lambda$run$0()V"), "the old name must survive as a fuse stub, got " + out);
		assertNotEquals("onFieldB", out.calleeOf("lambda$run$0()V"),
			"old name lambda$run$0 must not silently run the fieldB body, got " + out);
		assertTrue(out.isGhost("lambda$run$0()V"),
			"the refused pair must be degraded to a loud ghost stub, got " + out);
		// 新体不许丢掉：它拿到一个避障名（不是任何一个旧名字）。
		assertTrue(LambdaAligner.LAST_RESULT.inventedNames.size() >= 1,
			"the fresh fieldB body must get an invented name, got " + LambdaAligner.LAST_RESULT.inventedNames);
	}

	@Test
	@DisplayName("L22 disjoint-helper 否决：换名且本类 helper 集合不相交 —— 旧名 ghost，新体避障；共享 helper 对照仍配对")
	void disjointOwnHelpersVetoRenamedPairButKeepSharedHelperLane() {
		byte[] oldBytes = compile("DisjointHelperHost", B_OLD);
		byte[] newBytes = compile("DisjointHelperHost", B_NEW);
		View raw = new View(newBytes);

		// 前置基线：新版 javac 已经把两个 lambda 换位（$1 = helperC，$2 = audit）。
		assertEquals("helperC", raw.calleeOf("lambda$run$1()V"), "fixture premise: new $1 is the helperC lambda, got " + raw);
		assertEquals("audit", raw.calleeOf("lambda$run$2()V"), "fixture premise: new $2 is the audit lambda, got " + raw);
		assertEquals("helperB", new View(oldBytes).calleeOf("lambda$run$0()V"), "fixture premise: old $0 is the helperB lambda");

		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		View out = new View(aligned);

		// 否决分支：旧名 $0 不许被 helperC 的体顶掉 —— 它必须是响亮的 ghost 桩。
		assertTrue(out.hasMethod("lambda$run$0()V"), "the vetoed old name must survive as a ghost stub, got " + out);
		assertTrue(out.isGhost("lambda$run$0()V"),
			"the vetoed old name must be a loud ghost stub, got " + out);
		assertNotEquals("helperC", out.calleeOf("lambda$run$0()V"),
			"the vetoed old name must not run the new helperC body, got " + out);

		// 新体不许丢：helperC 落在一个非旧名的避障名上（也没有被 ghost 掉）。
		String helperCName = out.methodCalling("helperC");
		assertNotNull(helperCName, "the helperC body must still exist in the product, got " + out);
		assertFalse(Set.of("lambda$run$0()V", "lambda$run$1()V").contains(helperCName),
			"the helperC body must get a fresh name, not an old one: " + helperCName + " in " + out);

		// 合法对照：同样"换名"，但两侧共享 `audit` helper => 不属于不相交，必须正常配对。
		assertEquals("audit", out.calleeOf("lambda$run$1()V"),
			"a renamed pair sharing the same own-class helper must still be paired (control), got " + out);
		assertEquals("v2", out.constOf("lambda$run$1()V"),
			"the paired body must be the new one (audit(\"v2\")), got " + out);
	}

	// ------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------

	private static byte[] compile(String simpleName, String source) {
		return compileAll(Map.of("remold.test." + simpleName, source)).get("remold.test." + simpleName);
	}

	/** javac 现编（内存文件管理器）：与 {@code LambdaLvtStrictGateTest} 的造字节方式一致，带全量 -g。 */
	private static Map<String, byte[]> compileAll(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		StandardJavaFileManager std = compiler.getStandardFileManager(null, null, null);
		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override
			public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};

		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			});
		}

		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn", "-g"), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("fixture compile failed:");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}

	/** 产物字节码的可读视图：lambda 名字 -> 方法体里的业务调用 / 常量 / 幽灵标记。 */
	private static final class View {
		private final ClassNode cn;

		View(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		boolean hasMethod(String key) {
			return method(key) != null;
		}

		/** 方法体里第一条指向本类的业务调用名（跳过 ghost 熔断入口）。 */
		String calleeOf(String key) {
			MethodNode mn = method(key);
			if (mn == null) return null;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && cn.name.equals(mi.owner)
				    && !mi.name.equals("onOrphanInvoked") && !mi.name.equals("onBridgeInvoked")) {
					return mi.name;
				}
			}
			return null;
		}

		/** 第一个调用 {@code callee} 的 lambda 方法（返回 {@code name+desc}）。 */
		String methodCalling(String callee) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				if (callee.equals(calleeOf(mn.name + mn.desc))) return mn.name + mn.desc;
			}
			return null;
		}

		String constOf(String key) {
			MethodNode mn = method(key);
			if (mn == null) return null;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof LdcInsnNode ldc) return String.valueOf(ldc.cst);
			}
			return null;
		}

		boolean isGhost(String key) {
			MethodNode mn = method(key);
			if (mn == null) return false;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.name.equals("onOrphanInvoked")) return true;
			}
			return false;
		}

		/**
		 * 字段写位点 -> 该位点消费的 lambda 方法名（indy 的 implMethod）。
		 *
		 * <p>这正是老 CallSite 的绑定口径：{@code this.f = () -> body;} 编译成
		 * {@code aload_0; invokedynamic implMethod; putfield f}，所以 putfield 前一条就是 indy。</p>
		 */
		Map<String, String> putTargets() {
			Map<String, String> out = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (mn.name.startsWith("lambda$")) continue;
				AbstractInsnNode prev = null;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD) {
						if (prev instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null
						    && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof org.objectweb.asm.Handle h) {
							out.put(fi.name, h.getName());
						} else {
							out.put(fi.name, "<not-a-lambda:" + (prev == null ? "null" : prev.getClass().getSimpleName()) + ">");
						}
					}
					prev = insn;
				}
			}
			return out;
		}

		/** 按 {@code name+desc} 精确取（javac 会复用编号：{@code lambda$run$0()V} 与
		 *  {@code lambda$run$0()Ljava/lang/String;} 同时存在，只按名字取会张冠李戴）。 */
		private MethodNode method(String key) {
			for (MethodNode mn : cn.methods) if ((mn.name + mn.desc).equals(key)) return mn;
			return null;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				String key = mn.name + mn.desc;
				view.put(key, (isGhost(key) ? "GHOST:" : "") + calleeOf(key) + "/" + constOf(key));
			}
			return view + " puts=" + putTargets();
		}
	}
}

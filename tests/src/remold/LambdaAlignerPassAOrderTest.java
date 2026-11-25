package remold;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A1/A2 guards for {@link LambdaAligner}'s Step 2 <b>PASS_A ordering</b>.
 *
 * <p><b>Defect</b>: PASS_A used to walk the new lambdas in host-sequence order and let the first
 * legal candidate win by same-name evidence. A newly inserted lambda whose consumer is not a named
 * field write (a Kotlin local {@code val ins2 = { z -> z + 999 }}, i.e. <i>no unique PUT anchor</i>)
 * therefore reached the same-name fallback before the lambda that <i>does</i> carry the anchor
 * ({@code PUT:anchored}) had a chance to claim its old slot. The old slot was then taken by the new
 * body: a live instance holding the old lambda silently ran the new semantics.</p>
 *
 * <p><b>Shape of the fix</b>: PASS_A runs in two segments — every candidate that resolves to a
 * unique PUT anchor in the old group pairs first; the remaining candidates only then use the
 * existing fallback (anchor / strict-overlap / same-name / first-legal).</p>
 *
 * <p><b>Assertions land on the bytecode end state</b>: which body the old {@code lambda$run$N} name
 * carries after alignment, and which name the field write site consumes — i.e. exactly what a live
 * instance's old call site will execute. No log or counter is used as a proxy.</p>
 *
 * <p><b>Anti-vacuity controls</b> (the brief's two required kinds):</p>
 * <ul>
 *   <li><b>去触发</b>: reverting the two-segment split (the mutation check) must turn
 *       {@link #anchorSlotIsNotStolenInEitherDeclarationOrder()} red.</li>
 *   <li><b>换无关输入</b>: {@link #anchorFreeInputStillPairsEverythingViaLegacyFallback()} keeps
 *       both lambdas consumed by a method call (no {@code PUT:} consumer at all) and asserts the
 *       legacy fallback still pairs every lambda — so A2's "pairing capability must not regress" is
 *       locked, and the anchor assertion above is not a property that holds for every input.</li>
 * </ul>
 */
class LambdaAlignerPassAOrderTest {

	private static final String HOST = "remold.test.PassAOrderHost";
	private static final String ANCHORED_NAME = "lambda$run$0()V";

	// ------------------------------------------------------------------------------------------
	// fixtures
	//
	// old: $0 = this.anchored = () -> anchoredBodyV1();   (unique PUT:anchored anchor)
	//      $1 = this.kept     = () -> keptBodyV1();
	//
	// new (inserted first): $0 = inserted (local, no PUT consumer) -> insertedBodyV2();
	//                       $1 = this.anchored -> anchoredBodyV2();   (keeps the anchor)
	//                       $2 = this.kept     -> keptBodyV2();
	//
	// Bodies change callees (so Step 1 hash / skeleton passes cannot pair them) and the inserted
	// lambda precedes the anchored one, so the pre-fix PASS_A hands lambda$run$0 to the inserted
	// body.
	// ------------------------------------------------------------------------------------------
	private static final String OLD = """
		package remold.test;
		public class PassAOrderHost {
		    private Runnable anchored;
		    private Runnable kept;
		    static void anchoredBodyV1() { System.out.println("anchored v1"); }
		    static void keptBodyV1() { System.out.println("kept v1"); }
		    public void run() {
		        this.anchored = () -> anchoredBodyV1();
		        this.kept = () -> keptBodyV1();
		    }
		}
		""";

	private static final String NEW_ANCHOR_FREE_DECLARED_FIRST = """
		package remold.test;
		public class PassAOrderHost {
		    private Runnable anchored;
		    private Runnable kept;
		    static void anchoredBodyV2() { System.out.println("anchored v2"); }
		    static void keptBodyV2() { System.out.println("kept v2"); }
		    static void insertedBodyV2() { System.out.println("inserted v2"); }
		    public void run() {
		        Runnable inserted = () -> insertedBodyV2();
		        this.anchored = () -> anchoredBodyV2();
		        this.kept = () -> keptBodyV2();
		    }
		}
		""";

	/** Same edit as {@link #NEW_ANCHOR_FREE_DECLARED_FIRST}, but the anchor-free lambda is declared second. */
	private static final String NEW_ANCHOR_FREE_DECLARED_SECOND = """
		package remold.test;
		public class PassAOrderHost {
		    private Runnable anchored;
		    private Runnable kept;
		    static void anchoredBodyV2() { System.out.println("anchored v2"); }
		    static void keptBodyV2() { System.out.println("kept v2"); }
		    static void insertedBodyV2() { System.out.println("inserted v2"); }
		    public void run() {
		        this.anchored = () -> anchoredBodyV2();
		        Runnable inserted = () -> insertedBodyV2();
		        this.kept = () -> keptBodyV2();
		    }
		}
		""";

	// ------------------------------------------------------------------------------------------
	// A2 control: identical edit shape, but both lambdas are consumed by execute(...) calls, so
	// there is no PUT consumer on either side and PASS_A's anchored segment must stay empty. The
	// legacy same-name fallback has to keep pairing every lambda (no invented names, no ghosts).
	// ------------------------------------------------------------------------------------------
	private static final String CALL_OLD = """
		package remold.test;
		public class CallConsumedOrderHost {
		    static void anchoredBodyV1() { System.out.println("anchored v1"); }
		    static void keptBodyV1() { System.out.println("kept v1"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        execute(() -> anchoredBodyV1());
		        execute(() -> keptBodyV1());
		    }
		}
		""";

	private static final String CALL_NEW = """
		package remold.test;
		public class CallConsumedOrderHost {
		    static void anchoredBodyV2() { System.out.println("anchored v2"); }
		    static void keptBodyV2() { System.out.println("kept v2"); }
		    static void execute(Runnable r) { r.run(); }
		    public void run() {
		        execute(() -> anchoredBodyV2());
		        execute(() -> keptBodyV2());
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
	@DisplayName("A1：无论无锚点新 lambda 声明在锚点候选之前还是之后，锚点槽位都归锚点候选")
	void anchorSlotIsNotStolenInEitherDeclarationOrder() {
		byte[] oldBytes = compile(HOST, OLD);
		byte[] declaredFirst = compile(HOST, NEW_ANCHOR_FREE_DECLARED_FIRST);
		byte[] declaredSecond = compile(HOST, NEW_ANCHOR_FREE_DECLARED_SECOND);

		// Preconditions: the old side really carries a unique PUT anchor, and in both new fixtures
		// javac slot order disagrees with field identity (so PASS_A cannot lean on name order alone).
		View rawOld = new View(oldBytes);
		assertEquals("lambda$run$0", rawOld.putTargets().get("anchored"),
			"fixture premise: old $0 is the anchored lambda, got " + rawOld);
		assertEquals("lambda$run$1", new View(declaredFirst).putTargets().get("anchored"),
			"fixture premise: with the anchor-free lambda declared first, anchored is new $1");
		assertEquals("lambda$run$0", new View(declaredSecond).putTargets().get("anchored"),
			"fixture premise: with the anchor-free lambda declared second, anchored is new $0");
		for (byte[] newBytes : List.of(declaredFirst, declaredSecond)) {
			View rawNew = new View(newBytes);
			String anchorFreeName = rawNew.methodCalling("insertedBodyV2");
			assertFalse(anchorFreeName.isEmpty(),
				"fixture premise: the inserted body exists in every new version, got " + rawNew);
			assertTrue(rawNew.consumerOf(anchorFreeName).isEmpty(),
				"fixture premise: the anchor-free lambda must expose no consumer tag (local variable), got "
					+ rawNew.consumerOf(anchorFreeName) + " for " + rawNew);
		}

		// End state for both declaration orders (each needs its own align() call + LAST_RESULT read).
		for (byte[] newBytes : List.of(declaredFirst, declaredSecond)) {
			byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
			View out = new View(aligned);
			LambdaAligner.AlignmentStats stats = LambdaAligner.LAST_RESULT.stats;
			String order = new View(newBytes).putTargets().get("anchored");

			// 1. the old anchor slot carries the anchored lambda's NEW body.
			assertEquals("anchoredBodyV2", out.calleeOf(ANCHORED_NAME),
				"old anchor name lambda$run$0 must carry the anchored body (anchored@new=" + order + "), got " + out);
			// 2. the anchored field write site still consumes that old name (what live call sites run).
			assertEquals("lambda$run$0", out.putTargets().get("anchored"),
				"the anchored field write site must keep consuming lambda$run$0 (anchored@new=" + order + "), got " + out);
			// 3. the anchor-free body must not sit on the anchor slot, but must still exist.
			assertFalse(ANCHORED_NAME.equals(out.methodCalling("insertedBodyV2")),
				"the anchor-free inserted body must not take the anchored slot (anchored@new=" + order + "), got " + out);
			assertFalse(out.methodCalling("insertedBodyV2").isEmpty(),
				"the inserted body must still exist in the product (anchored@new=" + order + "), got " + out);
			assertTrue(stats.passAPairs >= 1,
				"the anchored pair must be decided in PASS_A (anchored@new=" + order + "), got " + stats);
		}
	}

	@Test
	@DisplayName("A1 确定性：同一输入两次 align 产物逐字节一致，两种声明顺序得到相同的锚点绑定")
	void anchorDecisionIsDeterministicAndOrderIndependent() {
		byte[] oldBytes = compile(HOST, OLD);
		byte[] declaredFirst = compile(HOST, NEW_ANCHOR_FREE_DECLARED_FIRST);
		byte[] declaredSecond = compile(HOST, NEW_ANCHOR_FREE_DECLARED_SECOND);

		byte[] alignedFirst = LambdaAligner.align(oldBytes, declaredFirst);
		byte[] alignedFirstAgain = LambdaAligner.align(oldBytes, declaredFirst);
		assertArrayEquals(alignedFirst, alignedFirstAgain,
			"two alignments of the same input must produce identical bytecode");

		String bindingFirst = new View(alignedFirst).putTargets().get("anchored") + "/"
			+ new View(alignedFirst).calleeOf(ANCHORED_NAME);
		String bindingSecond = new View(LambdaAligner.align(oldBytes, declaredSecond)).putTargets().get("anchored") + "/"
			+ new View(LambdaAligner.align(oldBytes, declaredSecond)).calleeOf(ANCHORED_NAME);
		assertEquals(bindingFirst, bindingSecond,
			"the anchored binding must not depend on declaration order, got first=" + bindingFirst
				+ " second=" + bindingSecond);
		assertEquals("lambda$run$0/anchoredBodyV2", bindingFirst,
			"both orders must bind the anchored old name to the anchored new body, got " + bindingFirst);
	}

	@Test
	@DisplayName("A2 对照：去掉 PUT 锚点后，既有回退仍把每个 lambda 都配上（无自造名/无幽灵）")
	void anchorFreeInputStillPairsEverythingViaLegacyFallback() {
		byte[] oldBytes = compile("remold.test.CallConsumedOrderHost", CALL_OLD);
		byte[] newBytes = compile("remold.test.CallConsumedOrderHost", CALL_NEW);

		View rawOld = new View(oldBytes);
		View rawNew = new View(newBytes);
		assertTrue(rawOld.putTargets().isEmpty() && rawNew.putTargets().isEmpty(),
			"control fixture must not write any field (no PUT anchor), got old=" + rawOld + " new=" + rawNew);

		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		View out = new View(aligned);

		// The legacy fallback must keep both old call sites paired with the new bodies: same-name
		// evidence pairs $0->$0 and $1->$1, so no name is invented and no old name is ghosted.
		assertEquals("anchoredBodyV2", out.calleeOf("lambda$run$0()V"),
			"legacy fallback must pair the first lambda in place, got " + out);
		assertEquals("keptBodyV2", out.calleeOf("lambda$run$1()V"),
			"legacy fallback must pair the second lambda in place, got " + out);
		assertTrue(LambdaAligner.LAST_RESULT.inventedNames.isEmpty(),
			"no fresh name should be needed without an anchor in play, got "
				+ LambdaAligner.LAST_RESULT.inventedNames);
		assertFalse(out.isGhost("lambda$run$0()V"), "old $0 must not be ghosted, got " + out);
		assertFalse(out.isGhost("lambda$run$1()V"), "old $1 must not be ghosted, got " + out);
		int pairs = LambdaAligner.LAST_RESULT.stats.step1Pairs
			+ LambdaAligner.LAST_RESULT.stats.passAPairs
			+ LambdaAligner.LAST_RESULT.stats.passBPairs;
		assertTrue(pairs >= 2, "both lambdas must be paired by the fallback path, got "
			+ LambdaAligner.LAST_RESULT.stats);
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.anchorPairs,
			"the control input must not produce anchor-decided pairs, got "
				+ LambdaAligner.LAST_RESULT.stats);
	}

	// ------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------

	private static byte[] compile(String dotName, String source) {
		return compileInMemory(Map.of(dotName, source)).get(dotName);
	}

	/** In-memory javac with full {@code -g}: capture/consumer evidence reads LocalVariableTable. */
	private static Map<String, byte[]> compileInMemory(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("test JVM has no system Java compiler");
		StandardJavaFileManager std = compiler.getStandardFileManager(null, null, null);
		Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				buffers.put(className, buffer);
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return buffer;
					}
				};
			}
		};
		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			URI uri = URI.create("string:///" + entry.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return entry.getValue();
				}
			});
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn", "-g"), null, units).call());
		if (!ok) {
			StringBuilder message = new StringBuilder("fixture compilation failed:");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				message.append("\n  ").append(diagnostic.getKind()).append(' ')
					.append(diagnostic.getMessage(null));
			}
			throw new IllegalStateException(message.toString());
		}
		Map<String, byte[]> output = new LinkedHashMap<>();
		for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet()) {
			output.put(entry.getKey(), entry.getValue().toByteArray());
		}
		return output;
	}

	/** Read-only view of the aligned product: which body each {@code lambda$} name carries. */
	private static final class View {
		private final ClassNode cn;

		View(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		/** First business call in the lambda body (own-class calls only, ghost stubs excluded). */
		String calleeOf(String key) {
			MethodNode mn = method(key);
			if (mn == null) return "";
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && cn.name.equals(mi.owner)
					&& !mi.name.equals("onOrphanInvoked") && !mi.name.equals("onBridgeInvoked")) {
					return mi.name;
				}
			}
			return "";
		}

		/** Lambda method that calls {@code callee}, as {@code name+desc} ({@code ""} when absent). */
		String methodCalling(String callee) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				if (callee.equals(calleeOf(mn.name + mn.desc))) return mn.name + mn.desc;
			}
			return "";
		}

		boolean isGhost(String key) {
			MethodNode mn = method(key);
			if (mn == null) return false;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.name.equals("onOrphanInvoked")) return true;
			}
			return false;
		}

		/** Field name -> lambda method name consumed by its write site (the live call site binding). */
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
							out.put(fi.name, "<not-a-lambda>");
						}
					}
					prev = insn;
				}
			}
			return out;
		}

		/** Consumer tag of a lambda body in the raw (unaligned) class file, for fixture premises. */
		String consumerOf(String key) {
			MethodNode mn = method(key);
			if (mn == null) return "";
			Map<AbstractInsnNode, String> tags = LambdaBytecodeScanner.consumersOf(null, cn, mn);
			String found = "";
			for (String tag : tags.values()) {
				if (tag != null && !tag.isEmpty() && !found.equals(tag)) return "";
			}
			return found;
		}

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
				view.put(key, (isGhost(key) ? "GHOST:" : "") + calleeOf(key));
			}
			return view + " puts=" + putTargets();
		}
	}
}

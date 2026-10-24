import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import remold.AnnotationTransformer;
import remold.LambdaAligner;
import remold.RemoldAgent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the align-baseline mismatch that surfaces as
 * {@code NoSuchMethodError} after a redefine.
 *
 * <p>{@code sourceBytecodeCache} stores the source-level bytecode, where lambdas are still
 * instance methods. The JVM, however, runs the force-normalized form
 * ({@code static} with {@code this} prepended). Feeding LambdaAligner a raw old side against a
 * normalized new side makes it see the same lambda as "deleted + added": the resurrected ghost
 * keeps the <b>raw</b> descriptor, while every live {@code CallSite} is bound to the
 * <b>normalized</b> one. After redefine those call sites fail to resolve the method.
 *
 * <p>Both inputs of {@code align} must therefore share one form. The second test pins that
 * {@code RemoldAgent.normalizeAlignBaseline} is what performs the normalization on the old side.
 */
class LambdaForceBaselineTest {

	@TempDir
	Path dir;

	private boolean savedForce;

	@BeforeEach
	void enableForce() {
		savedForce = RemoldAgent.FORCE_STATIC_LAMBDA;
		RemoldAgent.FORCE_STATIC_LAMBDA = true;
	}

	@AfterEach
	void restoreForce() {
		RemoldAgent.FORCE_STATIC_LAMBDA = savedForce;
	}

	@Test
	void rawOldBaselineBreaksTheGhostButNormalizedOneDoesNot() throws Exception {
		byte[] v1raw = compile("V1", "public class T {"
			+ " public int y;"
			+ " public Runnable a(){ return () -> System.out.println(\"a\" + y); }"
			+ " public Runnable b(){ return () -> System.out.println(\"b\" + y); }"
			+ "}");
		byte[] v2raw = compile("V2", "public class T {"
			+ " public int y;"
			+ " public Runnable a(){ return () -> System.out.println(\"a\" + y); }"
			+ "}");

		byte[] v1forced = force(v1raw);
		byte[] v2forced = force(v2raw);

		// Sanity: with a raw old side the ghost of the deleted lambda keeps the instance form,
		// which no live CallSite expects. This is exactly the broken state users hit.
		Set<String> wrongGhost = descsOf(LambdaAligner.align(v1raw, v2forced), "lambda$b$0");
		assertFalse(wrongGhost.contains("(LT;)V"),
			"a raw old baseline must not yield a forced ghost; got " + wrongGhost);

		// With both sides normalized the ghost keeps the forced form and cached call sites resolve.
		Set<String> rightGhost = descsOf(LambdaAligner.align(v1forced, v2forced), "lambda$b$0");
		assertTrue(rightGhost.contains("(LT;)V"),
			"normalized old baseline must resurrect the ghost in forced form; got " + rightGhost);
	}

	@Test
	void remoldAgentNormalizesTheOldAlignBaseline() throws Exception {
		byte[] v1raw = compile("V3", "public class T {"
			+ " public int y;"
			+ " public Runnable a(){ return () -> System.out.println(\"a\" + y); }"
			+ "}");

		Method m = RemoldAgent.class.getDeclaredMethod(
			"normalizeAlignBaseline", byte[].class, String.class, ClassLoader.class);
		m.setAccessible(true);
		byte[] out = (byte[]) m.invoke(null, v1raw, "T", getClass().getClassLoader());

		assertTrue(descsOf(out, "lambda$a$0").contains("(LT;)V"),
			"normalizeAlignBaseline must force the old side so both align inputs share one form");
	}

	private byte[] compile(String tag, String source) throws Exception {
		Path srcDir = Files.createDirectories(dir.resolve(tag));
		Path outDir = Files.createDirectories(dir.resolve(tag + "-out"));
		Path file = srcDir.resolve("T.java");
		Files.writeString(file, source);

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "a system javac is required for this fixture");
		int rc = javac.run(null, null, null, "-g", "-d", outDir.toString(), file.toString());
		assertEquals(0, rc, "fixture compilation failed for " + tag);
		return Files.readAllBytes(outDir.resolve("T.class"));
	}

	private byte[] force(byte[] bytes) {
		String slash = new ClassReader(bytes).getClassName();
		AnnotationTransformer.HierarchyTree.register(bytes);
		return AnnotationTransformer.forceStaticLambdas(bytes, slash, getClass().getClassLoader());
	}

	/** All descriptors declared under {@code name}; ghosts and renamed originals may coexist. */
	private static Set<String> descsOf(byte[] bytes, String name) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		Set<String> out = new LinkedHashSet<>();
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) out.add(mn.desc);
		}
		return out;
	}
}

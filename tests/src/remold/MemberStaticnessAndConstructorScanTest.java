package remold;

import org.junit.jupiter.api.Test;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.RemovedMember;
import remold.ref.RemovedMemberReaders.Result;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成员静态性翻转（S5c / S5d）与构造器删除（V5-C1）的读者扫描与安全门断言。
 *
 * <h2>背景与危害</h2>
 * <ul>
 *   <li><b>S5c（实例方法翻转为静态方法）</b>：旧批外读者持有 {@code invokevirtual Hot.tag()V}，若重定义放行，
 *       调用方在执行该指令时抛出 {@code java.lang.IncompatibleClassChangeError}（ICCE）；</li>
 *   <li><b>S5d（静态方法翻转为实例方法）</b>：旧批外读者持有 {@code invokestatic Hot.tag()V}，若重定义放行，
 *       调用方在执行该指令时抛出 {@code java.lang.IncompatibleClassChangeError}（ICCE）；</li>
 *   <li><b>字段静态性翻转</b>：实例字段变静态字段（旧读者 {@code getfield} 抛 ICCE）或静态字段变实例字段
 *       （旧读者 {@code getstatic} 抛 ICCE）；</li>
 *   <li><b>V5-C1（删无参构造器）</b>：旧批外读者持有 {@code new Hot()}（{@code invokespecial Hot.<init>()V}），
 *       若重定义放行，调用方执行时抛出 {@code java.lang.NoSuchMethodError: Hot.<init>()V}。</li>
 * </ul>
 *
 * <h2>防护契约</h2>
 * <ul>
 *   <li>批外存在旧读者时：必须判定为不兼容（{@code incompatibleAccess} 或 {@code hits}），
 *       布局门阻断重定义（{@code LAYOUT-REJECT}），保留旧代码安全运行；</li>
 *   <li>调用方在同批次一起重编（S5e / 对照组）：新字节码调用点与新签名/新静态性匹配，无悬空读者，正常放行。</li>
 * </ul>
 */
class MemberStaticnessAndConstructorScanTest {

	/**
	 * S5c: 实例方法变静态方法，批外调用方未重编（仍为 invokevirtual），必须被拦截。
	 */
	@Test
	void instanceMethodToStaticWithStaleReaderIsRejected() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public String tag() { return \"A\"; } }",
				"p/Reader", "package p; public class Reader { public static String call(Hot h) { return h.tag(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public static String tag() { return \"B\"; } }"
			)
		);

		byte[] oldHotBytes = w.pre().get("p/Hot");
		byte[] newHotBytes = w.post().get("p/Hot");

		// 1. ClassDiff 必须识别出方法删除与新增（静态性翻转不是普通的方法体修改）
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldHotBytes, newHotBytes);
		assertTrue(diff.removedMethods.contains("tag()Ljava/lang/String;"),
			"old instance method must be recorded as removed in diff");
		assertTrue(diff.addedMethods.contains("tag()Ljava/lang/String;"),
			"new static method must be recorded as added in diff");

		// 2. RemovedMemberReaders 必须捕获到 ICCE 不兼容访问
		RemovedMember m = new RemovedMember("p/Hot", "tag", "()Ljava/lang/String;", false, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertTrue(r.hasReaders(), "stale invokevirtual reader must be reported");
		assertFalse(r.incompatibleAccess().isEmpty(), "staticness flip must report incompatibleAccess (ICCE)");
		assertEquals("p/Reader", r.incompatibleAccess().get(0).reader());
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"),
			"detail must specify IncompatibleClassChangeError: " + r.incompatibleAccess().get(0).detail());

		// 3. LayoutDecisionEngine 终局判决必须 REJECT
		RemovedMemberScan scan = new RemovedMemberScan(true, List.of("p.Reader"), List.of(), true);
		var decision = LayoutDecisionEngine.decide("p.Hot", diff.changedFields, "reject", Liveness.LIVE, Set.of("p.Hot"), scan);
		assertEquals(LayoutAction.REJECT, decision.action(),
			"LayoutDecisionEngine must reject staticness flip with stale out-of-batch reader");
	}

	/**
	 * S5d: 静态方法变实例方法，批外调用方未重编（仍为 invokestatic），必须被拦截。
	 */
	@Test
	void staticMethodToInstanceWithStaleReaderIsRejected() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public static String tag() { return \"A\"; } }",
				"p/Reader", "package p; public class Reader { public static String call() { return Hot.tag(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public String tag() { return \"B\"; } }"
			)
		);

		byte[] oldHotBytes = w.pre().get("p/Hot");
		byte[] newHotBytes = w.post().get("p/Hot");

		// 1. ClassDiff 必须识别出方法删除与新增
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldHotBytes, newHotBytes);
		assertTrue(diff.removedMethods.contains("tag()Ljava/lang/String;"),
			"old static method must be recorded as removed in diff");
		assertTrue(diff.addedMethods.contains("tag()Ljava/lang/String;"),
			"new instance method must be recorded as added in diff");

		// 2. RemovedMemberReaders 必须捕获到 ICCE 不兼容访问
		RemovedMember m = new RemovedMember("p/Hot", "tag", "()Ljava/lang/String;", false, false, true);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertTrue(r.hasReaders(), "stale invokestatic reader must be reported");
		assertFalse(r.incompatibleAccess().isEmpty(), "staticness flip must report incompatibleAccess (ICCE)");
		assertEquals("p/Reader", r.incompatibleAccess().get(0).reader());
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"),
			"detail must specify IncompatibleClassChangeError: " + r.incompatibleAccess().get(0).detail());

		// 3. LayoutDecisionEngine 终局判决必须 REJECT
		RemovedMemberScan scan = new RemovedMemberScan(true, List.of("p.Reader"), List.of(), true);
		var decision = LayoutDecisionEngine.decide("p.Hot", diff.changedFields, "reject", Liveness.LIVE, Set.of("p.Hot"), scan);
		assertEquals(LayoutAction.REJECT, decision.action(),
			"LayoutDecisionEngine must reject static-to-instance flip with stale out-of-batch reader");
	}

	/**
	 * S5e (对照组): 静态性翻转，但调用方同批次一起重编（字节码调用点已对齐新版本），正常放行。
	 */
	@Test
	void methodStaticnessFlipWithBatchRecompiledReaderPasses() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public String tag() { return \"A\"; } }",
				"p/Reader", "package p; public class Reader { public static String call(Hot h) { return h.tag(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public static String tag() { return \"B\"; } }",
				"p/Reader", "package p; public class Reader { public static String call(Hot h) { return Hot.tag(); } }"
			)
		);

		RemovedMember m = new RemovedMember("p/Hot", "tag", "()Ljava/lang/String;", false, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertFalse(r.hasReaders(), "recompiled reader does not use stale invokevirtual; no linkage error");
		assertTrue(r.incompatibleAccess().isEmpty());

		RemovedMemberScan scan = new RemovedMemberScan(true, List.of(), List.of(), true);
		var decision = LayoutDecisionEngine.decide("p.Hot", List.of(), "reject", Liveness.LIVE, Set.of("p.Hot", "p.Reader"), scan);
		assertEquals(LayoutAction.PASS, decision.action(),
			"LayoutDecisionEngine must PASS when reader is recompiled in the same batch");
	}

	/**
	 * 字段静态性翻转（实例字段变静态字段），批外调用方未重编，必须被拦截。
	 */
	@Test
	void instanceFieldToStaticWithStaleReaderIsRejected() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public int f = 1; }",
				"p/Reader", "package p; public class Reader { public static int read(Hot h) { return h.f; } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public static int f = 2; } "
			)
		);

		RemovedMember m = new RemovedMember("p/Hot", "f", "I", true, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertTrue(r.hasReaders(), "stale getfield reader must be reported");
		assertFalse(r.incompatibleAccess().isEmpty(), "field staticness flip must report incompatibleAccess (ICCE)");
		assertEquals("p/Reader", r.incompatibleAccess().get(0).reader());
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"));
	}

	/**
	 * 字段静态性翻转（静态字段变实例字段），批外调用方未重编，必须被拦截。
	 */
	@Test
	void staticFieldToInstanceWithStaleReaderIsRejected() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public static int f = 1; }",
				"p/Reader", "package p; public class Reader { public static int read() { return Hot.f; } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public int f = 2; } "
			)
		);

		RemovedMember m = new RemovedMember("p/Hot", "f", "I", true, false, true);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertTrue(r.hasReaders(), "stale getstatic reader must be reported");
		assertFalse(r.incompatibleAccess().isEmpty(), "field staticness flip must report incompatibleAccess (ICCE)");
		assertEquals("p/Reader", r.incompatibleAccess().get(0).reader());
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"));
	}

	/**
	 * V5-C1: 删掉无参构造器，批外调用方仍通过 new Hot()（调用 Hot.<init>()V）访问，必须捕获 NoSuchMethodError 并 REJECT。
	 */
	@Test
	void removedNoArgConstructorWithStaleCallerIsRejected() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} public Hot(int x) {} }",
				"p/Reader", "package p; public class Reader { public static Object create() { return new Hot(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot(int x) {} }"
			)
		);

		byte[] oldHotBytes = w.pre().get("p/Hot");
		byte[] newHotBytes = w.post().get("p/Hot");

		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldHotBytes, newHotBytes);
		assertTrue(diff.removedMethods.contains("<init>()V"),
			"removed constructor <init>()V must be recorded as removed method in diff");

		RemovedMember m = new RemovedMember("p/Hot", "<init>", "()V", false, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertTrue(r.hasReaders(), "stale caller of removed constructor must be reported as a reader");
		assertFalse(r.hits().isEmpty(), "failure mode of removed constructor is NoSuchMethodError (hits)");
		assertEquals("p/Reader", r.hits().get(0).reader());

		RemovedMemberScan scan = new RemovedMemberScan(true, List.of("p.Reader"), List.of(), true);
		var decision = LayoutDecisionEngine.decide("p.Hot", diff.changedFields, "reject", Liveness.LIVE, Set.of("p.Hot"), scan);
		assertEquals(LayoutAction.REJECT, decision.action(),
			"LayoutDecisionEngine must reject constructor removal when stale callers exist");
	}

	/**
	 * V5-C1 对照组: 删掉无参构造器，但批外没有任何类调用它，正常放行。
	 */
	@Test
	void removedNoArgConstructorWithoutCallerPasses() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} public Hot(int x) {} }",
				"p/Reader", "package p; public class Reader { public static int dummy() { return 1; } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot(int x) {} }"
			)
		);

		RemovedMember m = new RemovedMember("p/Hot", "<init>", "()V", false, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m)
		);

		assertFalse(r.hasReaders(), "nobody calls Hot.<init>()V; removing it is safe");
	}

	// ------------------------------------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------------------------------------

	private record Worlds(Map<String, byte[]> pre, Map<String, byte[]> post) { }

	private static Worlds worlds(Map<String, String> v1, Map<String, String> v2) throws Exception {
		Path d1 = Files.createTempDirectory("staticness_v1");
		compileInto(d1, v1, null);
		Map<String, byte[]> pre = readClasses(d1);

		Map<String, byte[]> post = new LinkedHashMap<>(pre);
		if (!v2.isEmpty()) {
			Path d2 = Files.createTempDirectory("staticness_v2");
			compileInto(d2, v2, d1.toString());
			Map<String, byte[]> v2Classes = readClasses(d2);
			for (String name : v2.keySet()) {
				byte[] bytes = v2Classes.get(name);
				if (bytes == null) throw new IllegalStateException("v2 did not produce " + name);
				post.put(name, bytes);
			}
		}
		return new Worlds(pre, post);
	}

	private static void compileInto(Path dir, Map<String, String> sources, String extraClasspath) throws Exception {
		List<File> files = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			Path src = dir.resolve(e.getKey() + ".java");
			Files.createDirectories(src.getParent());
			Files.writeString(src, e.getValue(), StandardCharsets.UTF_8);
			files.add(src.toFile());
		}

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("JDK required");
		List<String> options = new ArrayList<>(List.of("-d", dir.toString(), "-g", "-nowarn"));
		if (extraClasspath != null) {
			options.add("-cp");
			options.add(extraClasspath);
		}
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(null, fm, null, options, null, fm.getJavaFileObjectsFromFiles(files)).call();
			if (!Boolean.TRUE.equals(ok)) {
				throw new IllegalStateException("compilation failed: " + sources.keySet());
			}
		}
	}

	private static Map<String, byte[]> readClasses(Path dir) throws Exception {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				String rel = dir.relativize(p).toString().replace('\\', '/');
				if (!rel.endsWith(".class")) continue;
				out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
			}
		}
		return out;
	}
}

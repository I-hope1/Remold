package remold;

import org.junit.jupiter.api.Test;
import remold.RemoldAgent.RemovedMemberScan;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.Ref;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前线 P0-3c：<b>与被删成员无关的常量池引用不得让读者扫描变成 inconclusive</b>。
 *
 * <p>缺陷形态（真机日志）：删掉 {@code Hot(int)}，调用方已同批次重编；扫描却报
 * {@code reader scan could not complete … (java/util/ArrayList.<init>(I)V 等无关引用 inconclusive)}，
 * 于是 fail-closed 拒绝。根因是归属只按 {@code name:desc} 匹配——{@code ArrayList.<init>(I)V}
 * 与 {@code Hot.<init>(I)V} 的 name+desc 相同，随后用读者字符串里的 JDK owner 去解析，
 * 类型世界当然没有这个类，得到 {@code UNKNOWN}，被当成"判不了"。</p>
 *
 * <h2>为什么可以在解析前就证明无关（JVMS）</h2>
 * <ul>
 *   <li><b>{@code <init>} / {@code <clinit>}（JVMS 5.4.3.3）</b>：构造器<b>不被继承</b>，方法解析
 *       只能落在 {@code ref.owner} 自己身上。因此 {@code ref.owner != member.owner} 就足以证明
 *       这条引用<b>永远</b>指不到被删构造器；owner 相等时仍然必须照常解析（不能跳过）。</li>
 *   <li><b>bootstrap / platform 前缀 owner</b>（{@code java/} 等）：这些包只能由 bootstrap / platform
 *       loader 定义，其祖先链与接口链也全部在 platform 世界内，永远不可能落到工程类成员上。
 *       该证明只对"被删成员 owner <b>不在</b>这些前缀下"成立；两边都在 platform 前缀时无法据此
 *       区分，必须保持原来的 resolve → {@code UNKNOWN} → inconclusive 的 fail-closed 行为。</li>
 * </ul>
 *
 * <p>本类同时锁住"不过度抑制"：真正的陈旧读者、以及类型信息缺失的引用，仍必须被报出来。</p>
 */
class UnrelatedRefReaderScanTest {

	/**
	 * 删除 {@code Hot(int)}，批外读者只调用同描述符的 JDK 构造器（{@code new ArrayList<>(4)} 等）。
	 * 这些引用与被删构造器无关，扫描必须给出"无读者且无判不了"的结论。
	 */
	@Test
	void removedConstructorIgnoresUnrelatedJdkConstructorCalls() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} public Hot(int x) {} }",
				"p/Reader", "package p; public class Reader { public static Object[] build() {"
					+ " return new Object[] { new java.util.ArrayList<>(4), new StringBuilder(16),"
					+ " new java.util.HashMap<>(8) }; } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} }"
			)
		);

		// 前置基线：无关引用确实在读者的常量池里，且确实与 <init>(I)V 同 name+desc——
		// 这正是旧实现只按 name+desc 归属后会误命中的东西。
		Set<String> readerRefs = refKeys(w.post().get("p/Reader"));
		assertTrue(readerRefs.contains("java/util/ArrayList.<init>(I)V"),
			"fixture precondition: reader must reference ArrayList.<init>(I)V, refs=" + readerRefs);
		assertTrue(readerRefs.contains("java/lang/StringBuilder.<init>(I)V"),
			"fixture precondition: reader must reference StringBuilder.<init>(I)V, refs=" + readerRefs);
		assertTrue(readerRefs.contains("java/util/HashMap.<init>(I)V"),
			"fixture precondition: reader must reference HashMap.<init>(I)V, refs=" + readerRefs);

		RemovedMember m = new RemovedMember("p/Hot", "<init>", "(I)V", false, false, false);
		Result r = find(w, m);

		assertTrue(r.inconclusive().isEmpty(),
			"unrelated JDK constructor refs must not make the scan inconclusive: " + r.inconclusive());
		assertTrue(r.hits().isEmpty(), "nobody calls Hot.<init>(I)V, so there must be no dangling reader: " + r.hits());
		assertFalse(r.hasReaders(), "removing an uncalled constructor must not be blocked by unrelated refs");
	}

	/**
	 * 前线 P0 的直接复现：删除 {@code Hot(int)}，调用方在<b>同批次</b>重编（不再调用该构造器），
	 * 但新字节码里仍留着 {@code new ArrayList<>(4)} 这类同描述符引用。必须判定为"无读者且无判不了"。
	 */
	@Test
	void removedConstructorIgnoresUnrelatedJdkCallsFromSameBatchReader() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} public Hot(int x) {} }",
				"p/Reader", "package p; public class Reader { public static Object[] build() {"
					+ " return new Object[] { new Hot(1), new java.util.ArrayList<>(4) }; } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} }",
				"p/Reader", "package p; public class Reader { public static Object[] build() {"
					+ " return new Object[] { new Hot(), new java.util.ArrayList<>(4) }; } }"
			)
		);

		// 前置基线（改动前）：旧的读者字节码里确实有指向被删构造器的引用。
		assertTrue(refKeys(w.pre().get("p/Reader")).contains("p/Hot.<init>(I)V"),
			"fixture precondition: the stale reader used to call Hot.<init>(I)V");
		// 改动后：同批次重编的读者不再引用被删构造器，但无关的 JDK 构造器引用仍在。
		Set<String> after = refKeys(w.post().get("p/Reader"));
		assertFalse(after.contains("p/Hot.<init>(I)V"),
			"precondition: recompiled reader must no longer reference the removed constructor");
		assertTrue(after.contains("java/util/ArrayList.<init>(I)V"),
			"precondition: the unrelated JDK constructor ref must still be present, refs=" + after);

		RemovedMember m = new RemovedMember("p/Hot", "<init>", "(I)V", false, false, false);
		Result r = find(w, m);

		assertTrue(r.inconclusive().isEmpty(),
			"same-batch recompiled caller + unrelated JDK refs must be conclusive: " + r.inconclusive());
		assertTrue(r.hits().isEmpty(), "recompiled caller does not need the removed constructor: " + r.hits());
		assertFalse(r.hasReaders(), "removing a constructor whose only remaining refs are unrelated must pass the scan");
	}

	/**
	 * 边界（fail-closed）：删除<b>实例方法</b>时，常量池里的 {@code InterfaceMethodref
	 * java/util/List.isEmpty()Z} 不能按 platform 前缀证明无关。
	 *
	 * <p>实例方法在解析之后还有 JVMS 5.4.6 向下分派：ref.owner 可以是受体的任何祖先，包括
	 * platform 接口/类。工程类完全可能实现 {@code List} 后再删掉自己的 {@code isEmpty()} 覆盖，
	 * 此时读者持有的正是 {@code List.isEmpty()Z}。前缀只证明"解析落不到工程成员"，不能证明
	 * "分派落不到"，所以必须保留 resolve → {@code UNKNOWN} → inconclusive 的 fail-closed 路径。</p>
	 */
	@Test
	void removedInstanceMethodWithPlatformOwnedRefStaysInconclusive() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public boolean isEmpty() { return false; } }",
				"p/Reader", "package p; public class Reader {"
					+ " public static boolean test(java.util.List<String> l) { return l.isEmpty(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public int value() { return 1; } }"
			)
		);

		Set<String> readerRefs = refKeys(w.post().get("p/Reader"));
		assertTrue(readerRefs.contains("java/util/List.isEmpty()Z"),
			"fixture precondition: reader must reference List.isEmpty()Z, refs=" + readerRefs);

		RemovedMember m = new RemovedMember("p/Hot", "isEmpty", "()Z", false, false, false);
		Result r = find(w, m);

		assertFalse(r.inconclusive().isEmpty(),
			"a virtual-dispatch candidate owned by a platform interface cannot be proven unrelated"
				+ " without the platform type world; keeping it inconclusive is the fail-closed answer: "
				+ r.inconclusive());
		assertFalse(r.hasReaders(), "inconclusive stays a separate bucket; it must not fabricate a hit");
	}

	/**
	 * 真实危险形态：抽象类实现 platform 接口并删掉自己的实现方法（抽象类允许这么做）。
	 * 批外读者持 {@code Runnable.run}，运行期 receiver 是 {@code Hot} 的实例 → {@code AbstractMethodError}。
	 * 扫描器必须<b>不能</b>把这条引用当成"与 Hot.run 无关"而直接跳过；至少要保留 inconclusive，
	 * 让布局门继续 fail-closed。
	 */
	@Test
	void deletingOverrideOfPlatformInterfaceIsNotSilentlyTreatedAsNoReader() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public abstract class Hot implements Runnable {"
					+ " public void run() {} }",
				"p/Reader", "package p; public class Reader {"
					+ " public static void go(Runnable r) { r.run(); } }"
			),
			Map.of(
				"p/Hot", "package p; public abstract class Hot implements Runnable {"
					+ " public int value() { return 1; } }"
			)
		);

		assertTrue(refKeys(w.post().get("p/Reader")).contains("java/lang/Runnable.run()V"),
			"fixture precondition: the reader dispatches through the platform interface");

		RemovedMember m = new RemovedMember("p/Hot", "run", "()V", false, false, false);
		Result r = find(w, m);

		assertFalse(r.inconclusive().isEmpty() || r.hasReaders(),
			"deleting an override reachable through a platform interface must stay visible to the gate"
				+ " (hit or inconclusive), never silently pass: hits=" + r.hits()
				+ " inconclusive=" + r.inconclusive());
	}

	/**
	 * 删除静态方法 {@code size()I}，批外读者只调用 {@code List.size()I}（同 name+desc 的 platform 方法）。
	 */
	@Test
	void removedStaticMethodIgnoresUnrelatedJdkCalls() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public static int size() { return 0; } }",
				"p/Reader", "package p; public class Reader { public static int n() {"
					+ " java.util.List<String> l = new java.util.ArrayList<>(4); return l.size(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public int value() { return 1; } }"
			)
		);

		Set<String> readerRefs = refKeys(w.post().get("p/Reader"));
		assertTrue(readerRefs.contains("java/util/List.size()I"),
			"fixture precondition: reader must reference List.size()I, refs=" + readerRefs);

		RemovedMember m = new RemovedMember("p/Hot", "size", "()I", false, false, true);
		Result r = find(w, m);

		assertTrue(r.inconclusive().isEmpty(),
			"unrelated platform call must not make the scan inconclusive: " + r.inconclusive());
		assertFalse(r.hasReaders(), "List.size()I is unrelated to Hot.size()I");
	}

	/**
	 * 混合用例（不过度抑制）：同一个批次里既有无关的 JDK 构造器引用，又有真正的陈旧读者。
	 * 无关引用必须被证明无关地跳过，但陈旧读者仍必须被报出。
	 */
	@Test
	void unrelatedJdkRefsDoNotSuppressARealStaleReader() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} public Hot(int x) {} }",
				"p/ReaderA", "package p; public class ReaderA {"
					+ " public static Object create() { return new Hot(1); } }",
				"p/ReaderB", "package p; public class ReaderB {"
					+ " public static Object make() { return new java.util.ArrayList<>(4); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public Hot() {} }"
			)
		);

		assertTrue(refKeys(w.post().get("p/ReaderA")).contains("p/Hot.<init>(I)V"),
			"fixture precondition: ReaderA is a real stale reader");
		assertTrue(refKeys(w.post().get("p/ReaderB")).contains("java/util/ArrayList.<init>(I)V"),
			"fixture precondition: ReaderB holds the unrelated JDK ref");

		RemovedMember m = new RemovedMember("p/Hot", "<init>", "(I)V", false, false, false);
		Result r = find(w, m);

		assertTrue(r.inconclusive().isEmpty(),
			"the unrelated JDK ref must not poison the scan: " + r.inconclusive());
		assertEquals(1, r.hits().size(),
			"exactly the genuine stale reader must be reported: " + r.hits());
		assertEquals("p/ReaderA", r.hits().get(0).reader(),
			"the unrelated JDK ref must not be reported as a reader (no over-suppression, no over-report)");
		assertTrue(r.hasReaders(), "a real stale reader must still block the change");
	}

	/**
	 * fail-closed 保留：引用 owner 的工程类型信息缺失（无法证明无关）时，仍必须报 inconclusive。
	 */
	@Test
	void missingProjectTypeInfoStillReportsInconclusive() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Hot", "package p; public class Hot { public int foo() { return 1; } }",
				"q/Unknown", "package q; public class Unknown { public int foo() { return 2; } }",
				"p/Reader", "package p; public class Reader {"
					+ " public static int call(q.Unknown u) { return u.foo(); } }"
			),
			Map.of(
				"p/Hot", "package p; public class Hot { public int value() { return 1; } }"
			)
		);

		// 模拟"类型世界取不到 q/Unknown"：读者字节码里有，解析世界没有。
		Map<String, byte[]> pre = without(w.pre(), "q/Unknown");
		Map<String, byte[]> post = without(w.post(), "q/Unknown");

		RemovedMember m = new RemovedMember("p/Hot", "foo", "()I", false, false, false);
		Result r = RemovedMemberReaders.find(
			RemovedMemberReaders.universe(pre),
			RemovedMemberReaders.universe(post),
			post,
			List.of(m));

		assertFalse(r.inconclusive().isEmpty(),
			"an unresolvable project-type ref must stay inconclusive (fail-closed), never be silently treated as no reader");
		assertTrue(r.inconclusive().get(0).contains("q/Unknown"),
			"the inconclusive entry must name the unresolved owner for diagnosability: " + r.inconclusive());
		assertFalse(r.hasReaders(), "inconclusive is reported separately from hits; it must not fabricate a reader");
	}

	/**
	 * 平台前缀证明的边界：被删成员 owner <b>也</b>在 platform 前缀下时，无法用前缀证明无关，
	 * 必须保持原 resolve → UNKNOWN → inconclusive 的 fail-closed 路径。
	 *
	 * <p>合成输入：真实扫描只 diff 工程批次类，不会产出 {@code java/...} 的 RemovedMember；
	 * 本用例锁的是纯函数判据的<b>充分条件</b>——"仅当被删成员不在 platform 前缀下才可跳过"。</p>
	 */
	@Test
	void platformOwnedRemovedMemberIsNotSkippedByPrefixProof() throws Exception {
		Worlds w = worlds(
			Map.of(
				"p/Reader", "package p; public class Reader {"
					+ " public static int n(java.util.List<String> l) { return l.size(); } }"
			),
			Map.of()
		);

		RemovedMember m = new RemovedMember("java/util/List", "size", "()I", false, false, true);
		Result r = RemovedMemberReaders.find(
			Map.of(),
			Map.of(),
			w.post(),
			List.of(m));

		assertFalse(r.inconclusive().isEmpty(),
			"when both the removed member and the ref are platform-owned, the prefix proof does not apply"
				+ " — the scan must stay fail-closed: " + r.inconclusive());
	}

	/**
	 * 拒绝日志要能直接看到是谁在引用：describeFindings 必须带上前 3 个点分 reader 类名，
	 * 同时保留既有断言依赖的 "out-of-batch reader" 子串。
	 */
	@Test
	void rejectDiagnosticsNameTheOutOfBatchReaders() {
		RemovedMemberScan two = new RemovedMemberScan(
			true, List.of("p.ReaderA", "p.ReaderB"), List.of(), false, false, List.of());
		String text = LayoutDecisionEngine.describeFindings(two);
		assertTrue(text.contains("2 out-of-batch reader(s)"),
			"the stable summary substring must stay intact: " + text);
		assertTrue(text.contains("p.ReaderA"), "first reader must be named: " + text);
		assertTrue(text.contains("p.ReaderB"), "second reader must be named: " + text);

		RemovedMemberScan many = new RemovedMemberScan(
			true, List.of("p.A", "p.B", "p.C", "p.D"), List.of(), false, false, List.of());
		String truncated = LayoutDecisionEngine.describeFindings(many);
		assertTrue(truncated.contains("4 out-of-batch reader(s)"),
			"the count must still be exact: " + truncated);
		assertTrue(truncated.contains("p.A") && truncated.contains("p.B") && truncated.contains("p.C"),
			"the first three readers must be named: " + truncated);
		assertFalse(truncated.contains("p.D"),
			"only the first three reader names are listed: " + truncated);
	}

	// ------------------------------------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------------------------------------

	/** 与 {@code RemovedMemberScanner.scan} 组装类型世界的方式一致：post 给批次新字节，pre 再叠加批次旧字节。 */
	private record Worlds(Map<String, byte[]> pre, Map<String, byte[]> post) { }

	private static Result find(Worlds w, RemovedMember m) {
		return RemovedMemberReaders.find(
			RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()),
			w.post(),
			List.of(m));
	}

	private static Map<String, byte[]> without(Map<String, byte[]> classes, String internalName) {
		Map<String, byte[]> out = new LinkedHashMap<>(classes);
		out.remove(internalName);
		return out;
	}

	/** 读者常量池里的成员引用，形如 {@code owner.name(desc)}。 */
	private static Set<String> refKeys(byte[] classBytes) {
		Set<String> out = new LinkedHashSet<>();
		for (Ref ref : RemovedMemberReaders.refs(classBytes)) {
			out.add(ref.owner() + "." + ref.name() + ref.desc());
		}
		return out;
	}

	private static Worlds worlds(Map<String, String> v1, Map<String, String> v2) throws Exception {
		Path d1 = Files.createTempDirectory("unrelated_v1");
		compileInto(d1, v1, null);
		Map<String, byte[]> pre = readClasses(d1);

		Map<String, byte[]> post = new LinkedHashMap<>(pre);
		if (!v2.isEmpty()) {
			Path d2 = Files.createTempDirectory("unrelated_v2");
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

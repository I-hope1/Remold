import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.Ref;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 「删除成员的读者扫描」成本探针。
 *
 * <p>回答两件事，都用于决定 {@code docs/initfix/07-p05-probe-results.md} §13.6.4 里那三档扫描范围
 * 值不值得：</p>
 * <ol>
 *   <li><b>单类成本</b>：纯常量池扫描比"进方法体扫指令"快多少；</li>
 *   <li><b>各档总成本</b>：只扫已加载类 / 加上工程构建输出 / 加上整个 classpath（含 JDK 模块），
 *       在真实规模下各要多久，以及其中读字节占多少、扫描本身占多少。</li>
 * </ol>
 *
 * <p>同时做一条<b>正确性断言</b>：常量池扫描的结果必须是"进方法体扫指令"的<b>超集</b>——
 * 这正是"tag 15 必然回指 9/10/11"那条规范推论的可执行版本。若某天它不成立，说明推论有漏。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:readerScanProbe}（需要 {@code -javaagent} 提供 Instrumentation，
 * 但不需要 DCEVM）。</p>
 */
public final class ReaderScanProbe {

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		System.out.println("=== Reader scan cost probe ===");
		System.out.println("   java.version = " + System.getProperty("java.version"));
		System.out.println("   max heap = " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");

		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] Instrumentation unavailable; run with -javaagent:p05-agent.jar");
			return;
		}

		Path root = Path.of(System.getProperty("remold.projectRoot", System.getProperty("user.dir")));

		measurePerClass(root);
		measureLoadedClasses(inst);
		measureProjectClasses(root);
		measureWholeCorpus();

		System.out.println("\n=== READER SCAN PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------
	// A. 单类成本：纯常量池 vs 方法体精扫
	// ------------------------------------------------------------------------------------------------

	private static void measurePerClass(Path root) throws Exception {
		System.out.println("\n--- [A] per-class cost: constant pool only vs method-body walk ---");
		List<byte[]> sample = collectProjectClasses(root, 400);
		if (sample.isEmpty()) {
			System.out.println("   [SKIP] no project classes found under " + root);
			return;
		}

		long[] cp = new long[sample.size()];
		long[] legacy = new long[sample.size()];
		long bytes = 0;
		for (int i = 0; i < sample.size(); i++) {
			byte[] b = sample.get(i);
			bytes += b.length;
			long t0 = System.nanoTime();
			RemovedMemberReaders.refs(b);
			cp[i] = System.nanoTime() - t0;
			t0 = System.nanoTime();
			instructionRefs(b);
			legacy[i] = System.nanoTime() - t0;
		}
		reportNanos("constant pool only", cp);
		reportNanos("method-body walk", legacy);
		System.out.printf("   sample=%d classes, avg size=%.1f KiB, median speedup=%.1fx%n",
		                  sample.size(), bytes / 1024.0 / sample.size(),
		                  (double) median(legacy) / Math.max(1L, median(cp)));

		// 超集断言：常量池扫描必须覆盖方法体扫出来的每一个引用。
		int missing = 0;
		for (byte[] b : sample) {
			Set<Ref> fromCode = instructionRefs(b);
			Set<Ref> fromPool = new LinkedHashSet<>(RemovedMemberReaders.refs(b));
			for (Ref r : fromCode) {
				if (fromPool.contains(r)) continue;
				missing++;
				if (missing <= 3) System.out.println("   [MISS] " + r);
			}
		}
		check(missing == 0,
			"constant-pool scan must be a superset of the instruction walk (missing=" + missing + ")");
	}

	// ------------------------------------------------------------------------------------------------
	// B. 已加载类
	// ------------------------------------------------------------------------------------------------

	private static void measureLoadedClasses(Instrumentation inst) throws Exception {
		System.out.println("\n--- [B] scope 1: loaded classes only ---");
		Class<?>[] loaded = inst.getAllLoadedClasses();
		long[] total = new long[5];
		long[] readNanos = new long[5];
		long[] scanNanos = new long[5];
		int scanned = 0;
		long bytes = 0;
		for (int r = 0; r < total.length; r++) {
			long runStart = System.nanoTime();
			long read = 0;
			long scan = 0;
			int n = 0;
			long totalBytes = 0;
			for (Class<?> c : loaded) {
				long t0 = System.nanoTime();
				byte[] b = readClassBytes(c);
				long t1 = System.nanoTime();
				read += t1 - t0;
				if (b == null) continue;
				totalBytes += b.length;
				RemovedMemberReaders.refs(b);
				scan += System.nanoTime() - t1;
				n++;
			}
			total[r] = System.nanoTime() - runStart;
			readNanos[r] = read;
			scanNanos[r] = scan;
			if (r == 0) {
				scanned = n;
				bytes = totalBytes;
			}
		}
		System.out.printf("   loaded=%d, readable=%d, bytes=%.1f MiB%n",
		                  loaded.length, scanned, bytes / 1024.0 / 1024.0);
		reportNanos("total (read+scan)", total);
		reportNanos("  read only", readNanos);
		reportNanos("  scan only", scanNanos);
	}

	private static byte[] readClassBytes(Class<?> c) {
		String resource = "/" + c.getName().replace('.', '/') + ".class";
		try (InputStream in = c.getResourceAsStream(resource)) {
			if (in == null) return null;
			return in.readAllBytes();
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------------------------------------
	// C. 工程构建输出
	// ------------------------------------------------------------------------------------------------

	private static void measureProjectClasses(Path root) throws Exception {
		System.out.println("\n--- [C] scope 2: + project build output ---");
		List<byte[]> classes = collectProjectClasses(root, Integer.MAX_VALUE);
		if (classes.isEmpty()) {
			System.out.println("   [SKIP] no project classes found under " + root);
			return;
		}
		long bytes = 0;
		for (byte[] b : classes) bytes += b.length;

		long[] samples = new long[5];
		for (int r = 0; r < samples.length; r++) {
			long t0 = System.nanoTime();
			for (byte[] b : classes) RemovedMemberReaders.refs(b);
			samples[r] = System.nanoTime() - t0;
		}
		System.out.printf("   classes=%d, bytes=%.1f MiB (bytes already in memory)%n",
		                  classes.size(), bytes / 1024.0 / 1024.0);
		reportNanos("scan only", samples);
	}

	// ------------------------------------------------------------------------------------------------
	// D. 整个 classpath（含 JDK 模块）
	// ------------------------------------------------------------------------------------------------

	private static void measureWholeCorpus() throws Exception {
		System.out.println("\n--- [D] scope 3: whole classpath (class path entries + JDK modules) ---");

		long[] cpTotal = new long[5];
		long[] cpRead = new long[5];
		long[] cpScan = new long[5];
		int cpCount = 0;
		long cpBytes = 0;
		for (int r = 0; r < cpTotal.length; r++) {
			long[] acc = new long[4];
			long t0 = System.nanoTime();
			scanClassPathEntries(acc);
			cpTotal[r] = System.nanoTime() - t0;
			cpRead[r] = acc[1];
			cpScan[r] = acc[2];
			if (r == 0) {
				cpCount = (int) acc[3];
				cpBytes = acc[0];
			}
		}
		System.out.printf("   class path entries: classes=%d, bytes=%.1f MiB%n",
		                  cpCount, cpBytes / 1024.0 / 1024.0);
		reportNanos("  total (read+scan)", cpTotal);
		reportNanos("  read only", cpRead);
		reportNanos("  scan only", cpScan);

		long[] jdkTotal = new long[3];
		long[] jdkRead = new long[3];
		long[] jdkScan = new long[3];
		int jdkCount = 0;
		long jdkBytes = 0;
		for (int r = 0; r < jdkTotal.length; r++) {
			long[] acc = new long[4];
			long t0 = System.nanoTime();
			scanJdkModules(acc);
			jdkTotal[r] = System.nanoTime() - t0;
			jdkRead[r] = acc[1];
			jdkScan[r] = acc[2];
			if (r == 0) {
				jdkCount = (int) acc[3];
				jdkBytes = acc[0];
			}
		}
		System.out.printf("   jdk modules: classes=%d, bytes=%.1f MiB%n",
		                  jdkCount, jdkBytes / 1024.0 / 1024.0);
		reportNanos("  total (read+scan)", jdkTotal);
		reportNanos("  read only", jdkRead);
		reportNanos("  scan only", jdkScan);

		// 在同一份 JDK 语料上对照一次方法体精扫（只取前 2000 个，避免探针跑太久）
		long[] cp = new long[2000];
		long[] legacy = new long[2000];
		int n = 0;
		for (Path p : jdkClassFiles()) {
			if (n >= cp.length) break;
			byte[] b = Files.readAllBytes(p);
			long t0 = System.nanoTime();
			RemovedMemberReaders.refs(b);
			cp[n] = System.nanoTime() - t0;
			t0 = System.nanoTime();
			instructionRefs(b);
			legacy[n] = System.nanoTime() - t0;
			n++;
		}
		if (n > 0) {
			reportNanos("constant pool only (jdk)", Arrays.copyOf(cp, n));
			reportNanos("method-body walk   (jdk)", Arrays.copyOf(legacy, n));
			System.out.printf("   median speedup on jdk sample=%.1fx%n",
			                  (double) median(Arrays.copyOf(legacy, n)) / Math.max(1L, median(Arrays.copyOf(cp, n))));
		}
	}

	private static void scanClassPathEntries(long[] acc) throws Exception {
		for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
			if (entry.isBlank()) continue;
			File f = new File(entry);
			if (f.isDirectory()) {
				try (Stream<Path> walk = Files.walk(f.toPath())) {
					for (Path p : walk.filter(Files::isRegularFile).toList()) {
						if (!p.toString().endsWith(".class")) continue;
						long t0 = System.nanoTime();
						byte[] bytes = Files.readAllBytes(p);
						scanOne(bytes, acc, System.nanoTime() - t0);
					}
				}
			} else if (f.isFile() && entry.endsWith(".jar")) {
				try (JarFile jar = new JarFile(f)) {
					var entries = jar.entries();
					while (entries.hasMoreElements()) {
						JarEntry je = entries.nextElement();
						if (je.isDirectory() || !je.getName().endsWith(".class")) continue;
						try (InputStream in = jar.getInputStream(je)) {
							long t0 = System.nanoTime();
							byte[] bytes = in.readAllBytes();
							scanOne(bytes, acc, System.nanoTime() - t0);
						}
					}
				}
			}
		}
	}

	private static void scanJdkModules(long[] acc) throws Exception {
		for (Path p : jdkClassFiles()) {
			long t0 = System.nanoTime();
			byte[] bytes = Files.readAllBytes(p);
			scanOne(bytes, acc, System.nanoTime() - t0);
		}
	}

	/** {@code acc} = {bytes, readNanos, scanNanos, count}；读耗时由调用方计时后传入。 */
	private static void scanOne(byte[] classBytes, long[] acc, long readNanos) {
		acc[0] += classBytes.length;
		acc[1] += readNanos;
		long t0 = System.nanoTime();
		RemovedMemberReaders.refs(classBytes);
		acc[2] += System.nanoTime() - t0;
		acc[3]++;
	}

	private static List<Path> jdkClassFiles() throws Exception {
		FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
		Path modules = jrt.getPath("/modules");
		List<Path> out = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(modules)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				if (p.toString().endsWith(".class")) out.add(p);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------
	// 方法体精扫（旧实现的等价物，只用于对照与超集断言）
	// ------------------------------------------------------------------------------------------------

	private static Set<Ref> instructionRefs(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Set<Ref> out = new LinkedHashSet<>();
		if (cn.methods == null) return out;
		for (MethodNode m : cn.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode f) {
					out.add(new Ref("field", f.owner, f.name, f.desc));
				} else if (insn instanceof MethodInsnNode mi) {
					out.add(new Ref(mi.itf ? "interfaceMethod" : "method", mi.owner, mi.name, mi.desc));
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					if (indy.bsmArgs == null) continue;
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h) addHandle(out, h);
					}
				} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) {
					addHandle(out, h);
				}
			}
		}
		return out;
	}

	private static void addHandle(Set<Ref> out, Handle h) {
		switch (h.getTag()) {
			case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC ->
				out.add(new Ref("field", h.getOwner(), h.getName(), h.getDesc()));
			case Opcodes.H_INVOKEINTERFACE ->
				out.add(new Ref("interfaceMethod", h.getOwner(), h.getName(), h.getDesc()));
			case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL,
			     Opcodes.H_NEWINVOKESPECIAL ->
				// 接口里的 static / private 方法其常量池条目是 InterfaceMethodref(11)，
				// 必须按 isInterface 区分，否则和常量池扫描的结果对不上（探针第一版就栽在这）。
				out.add(new Ref(h.isInterface() ? "interfaceMethod" : "method",
				                h.getOwner(), h.getName(), h.getDesc()));
			default -> { }
		}
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	/**
	 * 只收集各子模块 {@code build/classes} 下的 class——不去遍历整个仓库
	 * （{@code .sources/} 与 Gradle 缓存会让 walk 又慢又大）。
	 */
	private static List<byte[]> collectProjectClasses(Path root, int limit) throws Exception {
		List<byte[]> out = new ArrayList<>();
		if (!Files.isDirectory(root)) return out;
		try (var modules = Files.newDirectoryStream(root)) {
			for (Path module : modules) {
				Path buildClasses = module.resolve("build").resolve("classes");
				if (!Files.isDirectory(buildClasses)) continue;
				try (Stream<Path> walk = Files.walk(buildClasses)) {
					for (Path p : walk.filter(Files::isRegularFile).toList()) {
						if (out.size() >= limit) return out;
						if (!p.toString().endsWith(".class")) continue;
						out.add(Files.readAllBytes(p));
					}
				}
			}
		}
		return out;
	}

	private static void reportNanos(String label, long[] nanos) {
		if (nanos.length == 0) return;
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		long min = sorted[0];
		long med = sorted[sorted.length / 2];
		long max = sorted[sorted.length - 1];
		if (med < 1_000_000L) {
			System.out.printf("   %-24s n=%d min=%.2fus median=%.2fus max=%.2fus%n",
			                  label, sorted.length, min / 1e3, med / 1e3, max / 1e3);
		} else {
			System.out.printf("   %-24s n=%d min=%.1fms median=%.1fms max=%.1fms%n",
			                  label, sorted.length, min / 1e6, med / 1e6, max / 1e6);
		}
	}

	private static long median(long[] values) {
		long[] s = values.clone();
		Arrays.sort(s);
		return s[s.length / 2];
	}

	private static void check(boolean cond, String msg) {
		if (cond) {
			passed++;
			System.out.println("   [PASS] " + msg);
		} else {
			failed++;
			System.out.println("   [FAIL] " + msg);
		}
	}
}

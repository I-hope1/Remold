import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import remold.RemoldAgent;

/**
 * Metaspace 增长与泄漏实测探针 (MetaspaceGrowthProbe)
 *
 * <p>针对用户反馈："metaspace增长是普通jbr的5x，查一下原因"，
 * 本探针在真实的 JBR DCEVM 运行时下，分别测量 Plain JBR 原生重定义 vs Remold 流水线重定义的
 * Metaspace 内存消耗增长，并做消融实验（Ablation）定位导致差异的核心根因。</p>
 */
public class MetaspaceGrowthProbe {

	private static final int ROUNDS = 25;
	private static Instrumentation inst;

	private static MemoryPoolMXBean getMetaspacePool() {
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getName().toLowerCase().contains("metaspace")) {
				return pool;
			}
		}
		return null;
	}

	private static long sampleMetaspaceUsed() {
		System.gc();
		System.runFinalization();
		try { Thread.sleep(30); } catch (InterruptedException ignored) {}
		System.gc();
		MemoryPoolMXBean p = getMetaspacePool();
		return p != null ? p.getUsage().getUsed() : -1;
	}

	private static long sampleMetaspaceCommitted() {
		MemoryPoolMXBean p = getMetaspacePool();
		return p != null ? p.getUsage().getCommitted() : -1;
	}

	private static void setAgentInstrumentation(Instrumentation i) throws Exception {
		Field f = RemoldAgent.class.getDeclaredField("inst");
		f.setAccessible(true);
		f.set(null, i);
	}

	private static void processRemold(Path changedFile) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, Set.of(changedFile), inst.getAllLoadedClasses());
	}

	private static byte[] compileSource(String fqcn, String source, File outDir) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("JDK compiler required");
		File srcFile = new File(outDir, fqcn.replace('.', '/') + ".java");
		srcFile.getParentFile().mkdirs();
		Files.writeString(srcFile.toPath(), source, StandardCharsets.UTF_8);

		try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			List<String> options = List.of("-proc:none", "-d", outDir.getAbsolutePath(), "-g", "-nowarn");
			Boolean ok = compiler.getTask(null, fm, null, options, null, fm.getJavaFileObjects(srcFile)).call();
			if (!Boolean.TRUE.equals(ok)) {
				throw new IllegalStateException("Compilation failed for " + fqcn);
			}
		}
		File classFile = new File(outDir, fqcn.replace('.', '/') + ".class");
		return Files.readAllBytes(classFile.toPath());
	}

	static class TestResult {
		final String label;
		final long startUsed;
		final long endUsed;
		final long deltaUsed;
		final double avgPerRoundKb;
		final long startLoadedClasses;
		final long endLoadedClasses;
		final long totalLoadedClassesDelta;

		TestResult(String label, long startUsed, long endUsed, long startLoadedClasses, long endLoadedClasses, long totalLoadedClassesDelta) {
			this.label = label;
			this.startUsed = startUsed;
			this.endUsed = endUsed;
			this.deltaUsed = endUsed - startUsed;
			this.avgPerRoundKb = (deltaUsed / 1024.0) / ROUNDS;
			this.startLoadedClasses = startLoadedClasses;
			this.endLoadedClasses = endLoadedClasses;
			this.totalLoadedClassesDelta = totalLoadedClassesDelta;
		}
	}

	public static void main(String[] args) throws Exception {
		inst = P05Agent.instrumentation();
		if (inst == null) {
			System.err.println("P05Agent.instrumentation() is null! Make sure agent is loaded.");
			System.exit(1);
		}
		setAgentInstrumentation(inst);

		System.out.println("==========================================================================");
		System.out.println("=== Metaspace Growth Probe: Plain JBR vs Remold Pipeline (ROUNDS=" + ROUNDS + ") ===");
		System.out.println("=== Java Runtime: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version") + " ===");
		System.out.println("==========================================================================\n");

		// 全局预热：消除 Remold 自身类首次加载的冷启动噪声
		warmupRemold();

		// 试验一：纯方法体修改（普通类，无 Lambda）
		System.out.println(">>> 试验一：纯方法体修改（普通类 SimpleHot，无 Lambda）<<<");
		TestResult r1_plain = runPlainJbrSimple(ROUNDS);
		TestResult r1_remold = runRemoldSimple(ROUNDS, true, true);
		TestResult r1_noLambdaAlign = runRemoldSimple(ROUNDS, false, true);

		// 试验二：含 Lambda 的类修改方法体
		System.out.println("\n>>> 试验二：含 Lambda 的类修改方法体（LambdaHot，含 3 个 Lambda）<<<");
		TestResult r2_plain = runPlainJbrLambda(ROUNDS);
		TestResult r2_remold = runRemoldLambda(ROUNDS, true);
		TestResult r2_noLambdaAlign = runRemoldLambda(ROUNDS, false);

		// 试验三：加字段与两阶段（FieldHot，新增字段触发 InitFix + TwoPhase）
		System.out.println("\n>>> 试验三：连续新增字段（FieldHot，每次新增 1 个字段）<<<");
		TestResult r3_plain = runPlainJbrAddFields(10);
		TestResult r3_remold = runRemoldAddFields(10);

		// 打印最终汇总比对表
		printSummaryTable(List.of(
			r1_plain, r1_remold, r1_noLambdaAlign,
			r2_plain, r2_remold, r2_noLambdaAlign,
			r3_plain, r3_remold
		));
	}

	private static void warmupRemold() throws Exception {
		System.out.println(">>> [WARMUP] 正在预热 Remold 流水线（加载 ASM 与内部类）...");
		File dir = Files.createTempDirectory("warmup").toFile();
		try {
			byte[] v0 = compileSource("probe.meta.WarmupHot",
				"package probe.meta; public class WarmupHot { public int m() { return 0; } }", dir);
			File classFile = new File(dir, "probe/meta/WarmupHot.class");
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.WarmupHot", true, cl);
			RemoldAgent.bytecodeCache.put("probe.meta.WarmupHot", v0);
			RemoldAgent.sourceBytecodeCache.put("probe.meta.WarmupHot", v0);

			for (int i = 1; i <= 3; i++) {
				compileSource("probe.meta.WarmupHot",
					"package probe.meta; public class WarmupHot { public int m() { return " + i + "; } }", dir);
				processRemold(classFile.toPath());
			}
			System.gc();
			try { Thread.sleep(50); } catch (InterruptedException ignored) {}
			System.gc();
			System.out.println(">>> [WARMUP] 预热完成，JVM Metaspace 基线已稳定。\n");
		} finally {
			deleteRecursively(dir);
		}
	}

	// -------------------------------------------------------------------------------------------------
	// 试验一：普通类方法体修改
	// -------------------------------------------------------------------------------------------------

	private static String genSimpleSource(int version) {
		return "package probe.meta;\n" +
			"public class SimpleHot {\n" +
			"    public int getA() { return " + (100 + version) + "; }\n" +
			"    public String getB() { return \"version_" + version + "\"; }\n" +
			"    public long getC() { return " + (1000L * version) + "; }\n" +
			"    public double getD() { return " + (3.14 * version) + "; }\n" +
			"    public Object getE() { return \"obj_" + version + "\"; }\n" +
			"}\n";
	}

	private static TestResult runPlainJbrSimple(int rounds) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		File dir = Files.createTempDirectory("plain_simple").toFile();
		try {
			byte[] v0 = compileSource("probe.meta.SimpleHot", genSimpleSource(0), dir);
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.SimpleHot", true, cl);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			for (int i = 1; i <= rounds; i++) {
				byte[] bytes = compileSource("probe.meta.SimpleHot", genSimpleSource(i), dir);
				inst.redefineClasses(new ClassDefinition(clazz, bytes));
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			TestResult res = new TestResult("1A. Plain JBR (SimpleHot)", startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			deleteRecursively(dir);
		}
	}

	private static TestResult runRemoldSimple(int rounds, boolean lambdaAlign, boolean initFix) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		boolean prevLambdaAlign = RemoldAgent.LAMBDA_ALIGN;
		boolean prevInitFix = RemoldAgent.INIT_FIX;
		File dir = Files.createTempDirectory("remold_simple").toFile();
		try {
			RemoldAgent.LAMBDA_ALIGN = lambdaAlign;
			RemoldAgent.INIT_FIX = initFix;

			byte[] v0 = compileSource("probe.meta.SimpleHot", genSimpleSource(0), dir);
			File classFile = new File(dir, "probe/meta/SimpleHot.class");
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.SimpleHot", true, cl);
			RemoldAgent.bytecodeCache.put("probe.meta.SimpleHot", v0);
			RemoldAgent.sourceBytecodeCache.put("probe.meta.SimpleHot", v0);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			Set<Class<?>> classesBefore = new HashSet<>();
			for (Class<?> c : inst.getAllLoadedClasses()) classesBefore.add(c);

			for (int i = 1; i <= rounds; i++) {
				byte[] bytes = compileSource("probe.meta.SimpleHot", genSimpleSource(i), dir);
				processRemold(classFile.toPath());
				if (i == 1 || i == 2) {
					Set<Class<?>> classesNow = new HashSet<>();
					for (Class<?> c : inst.getAllLoadedClasses()) classesNow.add(c);
					classesNow.removeAll(classesBefore);
					System.out.println("   [DEBUG 1B Round " + i + "] Newly loaded classes (" + classesNow.size() + "):");
					for (Class<?> c : classesNow) {
						System.out.println("      - " + c.getName() + " (loader=" + c.getClassLoader() + ", isHidden=" + c.isHidden() + ")");
					}
					classesBefore.addAll(classesNow);
				}
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			String name = "1B. Remold (SimpleHot" + (lambdaAlign ? "" : ", noAlign") + (initFix ? "" : ", noInitFix") + ")";
			TestResult res = new TestResult(name, startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			RemoldAgent.LAMBDA_ALIGN = prevLambdaAlign;
			RemoldAgent.INIT_FIX = prevInitFix;
			deleteRecursively(dir);
		}
	}

	// -------------------------------------------------------------------------------------------------
	// 试验二：含 Lambda 的类方法体修改
	// -------------------------------------------------------------------------------------------------

	private static String genLambdaSource(int version) {
		return "package probe.meta;\n" +
			"public class LambdaHot {\n" +
			"    public java.util.function.Supplier<String> s = () -> \"val_\" + " + version + ";\n" +
			"    public java.util.function.Function<Integer, Integer> f = x -> x + " + version + ";\n" +
			"    public java.util.function.BiConsumer<String, Integer> b = (k, v) -> {\n" +
			"        int temp = k.length() + v + " + version + ";\n" +
			"    };\n" +
			"    public String run() {\n" +
			"        return s.get() + \":\" + f.apply(" + version + ");\n" +
			"    }\n" +
			"}\n";
	}

	private static TestResult runPlainJbrLambda(int rounds) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		File dir = Files.createTempDirectory("plain_lambda").toFile();
		try {
			byte[] v0 = compileSource("probe.meta.LambdaHot", genLambdaSource(0), dir);
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.LambdaHot", true, cl);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			for (int i = 1; i <= rounds; i++) {
				byte[] bytes = compileSource("probe.meta.LambdaHot", genLambdaSource(i), dir);
				inst.redefineClasses(new ClassDefinition(clazz, bytes));
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			TestResult res = new TestResult("2A. Plain JBR (LambdaHot)", startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			deleteRecursively(dir);
		}
	}

	private static TestResult runRemoldLambda(int rounds, boolean lambdaAlign) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		boolean prevLambdaAlign = RemoldAgent.LAMBDA_ALIGN;
		File dir = Files.createTempDirectory("remold_lambda").toFile();
		try {
			RemoldAgent.LAMBDA_ALIGN = lambdaAlign;

			byte[] v0 = compileSource("probe.meta.LambdaHot", genLambdaSource(0), dir);
			File classFile = new File(dir, "probe/meta/LambdaHot.class");
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.LambdaHot", true, cl);
			RemoldAgent.bytecodeCache.put("probe.meta.LambdaHot", v0);
			RemoldAgent.sourceBytecodeCache.put("probe.meta.LambdaHot", v0);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			for (int i = 1; i <= rounds; i++) {
				byte[] bytes = compileSource("probe.meta.LambdaHot", genLambdaSource(i), dir);
				processRemold(classFile.toPath());
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			String name = "2B. Remold (LambdaHot" + (lambdaAlign ? "" : ", noAlign") + ")";
			TestResult res = new TestResult(name, startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			RemoldAgent.LAMBDA_ALIGN = prevLambdaAlign;
			deleteRecursively(dir);
		}
	}

	// -------------------------------------------------------------------------------------------------
	// 试验三：加字段与两阶段
	// -------------------------------------------------------------------------------------------------

	private static String genFieldSource(int fieldsCount) {
		StringBuilder sb = new StringBuilder();
		sb.append("package probe.meta;\n");
		sb.append("public class FieldHot {\n");
		for (int i = 1; i <= fieldsCount; i++) {
			sb.append("    public int f_").append(i).append(" = ").append(i * 10).append(";\n");
		}
		sb.append("    public int sum() { return 0; }\n");
		sb.append("}\n");
		return sb.toString();
	}

	private static TestResult runPlainJbrAddFields(int rounds) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		File dir = Files.createTempDirectory("plain_field").toFile();
		try {
			byte[] v0 = compileSource("probe.meta.FieldHot", genFieldSource(1), dir);
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.FieldHot", true, cl);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			for (int i = 2; i <= rounds + 1; i++) {
				byte[] bytes = compileSource("probe.meta.FieldHot", genFieldSource(i), dir);
				inst.redefineClasses(new ClassDefinition(clazz, bytes));
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			TestResult res = new TestResult("3A. Plain JBR (Add Fields)", startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			deleteRecursively(dir);
		}
	}

	private static TestResult runRemoldAddFields(int rounds) throws Exception {
		ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
		File dir = Files.createTempDirectory("remold_field").toFile();
		try {
			byte[] v0 = compileSource("probe.meta.FieldHot", genFieldSource(1), dir);
			File classFile = new File(dir, "probe/meta/FieldHot.class");
			URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()}, MetaspaceGrowthProbe.class.getClassLoader());
			Class<?> clazz = Class.forName("probe.meta.FieldHot", true, cl);
			RemoldAgent.bytecodeCache.put("probe.meta.FieldHot", v0);
			RemoldAgent.sourceBytecodeCache.put("probe.meta.FieldHot", v0);

			long startUsed = sampleMetaspaceUsed();
			long startLoaded = clBean.getLoadedClassCount();
			long startTotalLoaded = clBean.getTotalLoadedClassCount();

			Set<Class<?>> classesBefore = new HashSet<>();
			for (Class<?> c : inst.getAllLoadedClasses()) classesBefore.add(c);

			for (int i = 2; i <= rounds + 1; i++) {
				byte[] bytes = compileSource("probe.meta.FieldHot", genFieldSource(i), dir);
				processRemold(classFile.toPath());
				if (i == 2 || i == 3) {
					Set<Class<?>> classesNow = new HashSet<>();
					for (Class<?> c : inst.getAllLoadedClasses()) classesNow.add(c);
					classesNow.removeAll(classesBefore);
					System.out.println("   [DEBUG 3B Round " + (i - 1) + "] Newly loaded classes (" + classesNow.size() + "):");
					for (Class<?> c : classesNow) {
						System.out.println("      - " + c.getName() + " (loader=" + c.getClassLoader() + ", isHidden=" + c.isHidden() + ")");
					}
					classesBefore.addAll(classesNow);
				}
			}

			long endUsed = sampleMetaspaceUsed();
			long endLoaded = clBean.getLoadedClassCount();
			long endTotalLoaded = clBean.getTotalLoadedClassCount();

			TestResult res = new TestResult("3B. Remold (Add Fields)", startUsed, endUsed, startLoaded, endLoaded, endTotalLoaded - startTotalLoaded);
			printResult(res);
			return res;
		} finally {
			deleteRecursively(dir);
		}
	}

	// -------------------------------------------------------------------------------------------------
	// 结果输出与比对
	// -------------------------------------------------------------------------------------------------

	private static void printResult(TestResult r) {
		System.out.printf("   %-35s | 起始: %,7d KB | 结束: %,7d KB | 增量: %,6d KB (%6.2f KB/轮) | 类加载净增: %3d | 总加载增量: %3d%n",
			r.label, r.startUsed / 1024, r.endUsed / 1024, r.deltaUsed / 1024, r.avgPerRoundKb,
			(r.endLoadedClasses - r.startLoadedClasses), r.totalLoadedClassesDelta);
	}

	private static void printSummaryTable(List<TestResult> results) {
		System.out.println("\n=========================================================================================================");
		System.out.println("=== 综合测试结论与倍率比对表 (Metaspace Growth Summary Table) ===");
		System.out.println("=========================================================================================================");
		System.out.printf("%-35s | %10s | %10s | %10s | %12s | %12s | %8s%n",
			"测试场景 (Scenario)", "起始 (KB)", "终值 (KB)", "净增 (KB)", "平均 (KB/轮)", "总加载类增量", "倍率 (vs Plain)");
		System.out.println("---------------------------------------------------------------------------------------------------------");

		TestResult baseSimple = results.get(0);
		TestResult baseLambda = results.get(3);
		TestResult baseField = results.get(6);

		for (TestResult r : results) {
			double ratio = 1.0;
			if (r.label.startsWith("1B")) {
				ratio = baseSimple.deltaUsed > 0 ? (double) r.deltaUsed / baseSimple.deltaUsed : 1.0;
			} else if (r.label.startsWith("2B")) {
				ratio = baseLambda.deltaUsed > 0 ? (double) r.deltaUsed / baseLambda.deltaUsed : 1.0;
			} else if (r.label.startsWith("3B")) {
				ratio = baseField.deltaUsed > 0 ? (double) r.deltaUsed / baseField.deltaUsed : 1.0;
			}

			System.out.printf("%-35s | %,10d | %,10d | %,10d | %12.2f | %12d | %7.2fx%n",
				r.label, r.startUsed / 1024, r.endUsed / 1024, r.deltaUsed / 1024, r.avgPerRoundKb,
				r.totalLoadedClassesDelta, ratio);
		}
		System.out.println("=========================================================================================================\n");
	}

	private static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}
}

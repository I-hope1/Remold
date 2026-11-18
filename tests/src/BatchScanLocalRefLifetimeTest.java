import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次遍历的 <b>JNI 局部引用生命周期</b>回归锁（{@code docs/initfix/05-jvmti-heap.md} §「批次实例收集」）。
 *
 * <p>锁的是一次真实的 use-after-free 现场：{@code GetLoadedClasses} 的枚举局部帧若留到函数退出才弹，
 * {@code GetObjectsWithTags} 回收到的 {@code jobject}（同样分配在「当前帧」里）会随帧失效，其
 * {@code JNIHandleBlock} 槽位被随后的 {@code NewObjectArray} 结果数组/行数组复用——写进行里的就成了
 * 容器数组自己。当时之所以<b>静默</b>，是因为行数组用的是 {@code Object[]}，不触发
 * {@code ArrayStoreException}。</p>
 *
 * <p><b>为什么需要这个用例</b>：修复后行数组按 target 强类型，同类缺陷会抛 {@code ArrayStoreException}，
 * native 侧捕获后<b>整体退回逐类路径</b>——结果依然正确，只是没有性能收益。因此「集合等价」抓不住回归
 * （探针的 K=64 读数是唯一当场发现它的手段），这里的锁是<b>遍历次数</b>：批路径必须真的接合（1 次遍历），
 * 而不是悄悄退化成 K 次。</p>
 *
 * <p><b>规模为什么是 64 × 20</b>：槽位复用要求「枚举期回收的引用数」与「随后的数组分配量」同量级；
 * 4 类 × 3 实例这种小规模在本机 JVM 上<b>不会</b>复现（实测：同样的变异在小规模用例下仍是绿的，
 * 见 {@link #smallBatchStaysOnTheBatchPath()}）。16 类 × 20 实例既落在「单块 = 1 次遍历」的断言口径内，又足够触发该缺陷（探针最小复现是 4 类 × 3 实例）。</p>
 */
class BatchScanLocalRefLifetimeTest {

	// 16 是收集路径的单块上限（{@code InitFix.BATCH_TARGET_LIMIT}）：恰好 1 次遍历，且规模足够触发
	// 局部引用槽位复用的紧邻时序。K 再大就会按 ceil(K/16) 分块，断言口径要跟着变。
	private static final int TARGETS = 16;
	private static final int INSTANCES_PER_TARGET = 20;

	private static long walks() {
		long count = LibTool.heapWalkCount();
		assertTrue(count >= 0, "native heap-walk counter is unavailable (old libtool build);"
			+ " the batch-scan lifetime lock needs a library with nHeapWalkCount");
		return count;
	}

	private static Set<Object> identitySet(Object[] values) {
		Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(set, values);
		return set;
	}

	/**
	 * 规模锁：16 类 × 20 实例。局部引用生命周期一旦回归，这里会看到「行里混进容器数组」
	 * 或直接退化成逐类遍历。
	 */
	@Test
	void wideBatchKeepsItsOwnInstancesAndStaysOnOneWalk() {
		assertTrue(LibTool.batchScanIsNative(),
			"the packaged native library must expose the batch entry points; rebuild core/cpp/tool.cpp");

		Map<String, byte[]> compiled = compileFixtures(TARGETS);
		Loader loader = new Loader(compiled);
		Class<?>[] targets = new Class<?>[TARGETS];
		Object[][] created = new Object[TARGETS][];
		for (int t = 0; t < TARGETS; t++) {
			targets[t] = load(loader, fixtureName(t));
			created[t] = new Object[INSTANCES_PER_TARGET];
			for (int i = 0; i < INSTANCES_PER_TARGET; i++) {
				created[t][i] = instantiate(targets[t]);
			}
		}

		long before = walks();
		Object[][] rows = LibTool.getInstancesBatch(targets);
		long delta = walks() - before;

		// 反空转 + 生命周期锁：必须真的是「一次遍历」。退化到逐类说明收集回来的引用没能活到填数组。
		assertEquals(1L, delta,
			"one batch call over " + TARGETS + " targets must cost exactly one heap walk;"
				+ " a per-class fallback here means the collected references did not survive the fill");

		for (int t = 0; t < TARGETS; t++) {
			assertNotNull(rows[t], "target #" + t + " must have a row array");
			for (Object instance : rows[t]) {
				// use-after-free 的签名：行里出现别的类（尤其是容器数组 Object[]）的实例。
				assertEquals(targets[t], instance.getClass(),
					"row of " + targets[t].getName() + " must not contain a "
						+ instance.getClass().getName() + " (JNI local-ref slot reuse)");
			}
			assertTrue(identitySet(rows[t]).containsAll(identitySet(created[t])),
				"target #" + t + " must return the instances created for it");
			assertEquals(identitySet(LibTool.getInstances(targets[t])), identitySet(rows[t]),
				"batch collection for target #" + t + " must equal per-class enumeration");
		}

		// 第二轮：重复调用不得漂移。
		long secondBefore = walks();
		Object[][] again = LibTool.getInstancesBatch(targets);
		assertEquals(1L, walks() - secondBefore, "the second batch call must also stay on one heap walk");
		for (int t = 0; t < TARGETS; t++) {
			assertEquals(identitySet(rows[t]), identitySet(again[t]),
				"target #" + t + " must return the same instance set on a repeated call");
		}
	}

	/**
	 * 小规模健康检查：契约层面（精确类归属 + 与逐类结果一致 + 单次遍历）在小 K 上也必须成立。
	 * 注意它<b>不具备</b>捕获局部引用生命周期回归的能力（同量级才会触发槽位复用），
	 * 那条红线由 {@link #wideBatchKeepsItsOwnInstancesAndStaysOnOneWalk()} 守。
	 */
	@Test
	void smallBatchStaysOnTheBatchPath() {
		assertTrue(LibTool.batchScanIsNative(),
			"the packaged native library must expose the batch entry points; rebuild core/cpp/tool.cpp");

		Class<?>[] targets = { A.class, B.class, C.class, D.class };
		for (Class<?> target : targets) {
			for (int i = 0; i < 3; i++) instantiate(target);
		}

		long before = walks();
		Object[][] rows = LibTool.getInstancesBatch(targets);
		assertEquals(1L, walks() - before, "a 4-target batch call must cost exactly one heap walk");

		for (int t = 0; t < targets.length; t++) {
			for (Object instance : rows[t]) {
				assertEquals(targets[t], instance.getClass(),
					"row of " + targets[t].getSimpleName() + " must not contain a "
						+ instance.getClass().getSimpleName());
			}
			assertEquals(identitySet(LibTool.getInstances(targets[t])), identitySet(rows[t]),
				"batch collection for " + targets[t].getSimpleName() + " must equal per-class enumeration");
		}
	}

	// ------------------------------------------------------------------ fixtures

	static final class A { A() { } }
	static final class B { B() { } }
	static final class C { C() { } }
	static final class D { D() { } }

	private static String fixtureName(int index) {
		return "batchscanfix.F" + index;
	}

	/** 生成 {@code TARGETS} 个互不相关的夹具类源码（同类加载器、同包、各自一个 no-arg 构造器）。 */
	private static Map<String, byte[]> compileFixtures(int count) {
		Map<String, String> sources = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			String name = fixtureName(i);
			String simple = name.substring(name.lastIndexOf('.') + 1);
			sources.put(name, "package batchscanfix;\n"
				+ "public class " + simple + " {\n"
				+ "\tpublic int value;\n"
				+ "\tpublic " + simple + "() { this.value = " + i + "; }\n"
				+ "}\n");
		}
		return compile(sources);
	}

	private static Class<?> load(ClassLoader loader, String name) {
		try {
			return Class.forName(name, true, loader);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("failed to load fixture " + name, e);
		}
	}

	private static Object instantiate(Class<?> type) {
		try {
			return type.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("failed to instantiate fixture " + type.getName(), e);
		}
	}

	/** 只从内存字节码 defineClass 的加载器。 */
	static final class Loader extends ClassLoader {
		private final Map<String, byte[]> classes;

		Loader(Map<String, byte[]> classes) {
			super(BatchScanLocalRefLifetimeTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public InputStream getResourceAsStream(String name) {
			InputStream in = super.getResourceAsStream(name);
			if (in != null) return in;
			if (!name.endsWith(".class")) return null;
			String dot = name.substring(0, name.length() - ".class".length()).replace('/', '.');
			byte[] bytes = classes.get(dot);
			return bytes == null ? null : new ByteArrayInputStream(bytes);
		}
	}

	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("run this test on a JDK, not a JRE");
		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		ForwardingJavaFileManager<StandardJavaFileManager> memory =
			new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
				@Override
				public JavaFileObject getJavaFileForOutput(Location location, String className,
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
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			units.add(new SimpleJavaFileObject(
				URI.create("string:///" + entry.getKey().replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
				JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return entry.getValue();
				}
			});
		}
		List<String> options = List.of("-nowarn", "-proc:none", "-classpath",
			System.getProperty("java.class.path"));
		boolean ok = compiler.getTask(null, memory, diagnostics, options, null, units).call();
		if (!ok) throw new IllegalStateException("fixture compile failed: " + diagnostics.getDiagnostics());
		return out;
	}
}

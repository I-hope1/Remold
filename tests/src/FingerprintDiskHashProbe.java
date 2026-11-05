import remold.RemoldAgent;
import remold.util.CRC64;
import remold.util.LongLongMap;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task-2 reproduction: after one successful round that renames anonymous classes, a second watcher
 * round over the same unchanged disk files must be a no-op.
 *
 * <p>Drives {@code RemoldAgent.processChanges} directly (reflection) so the disk fingerprint table
 * is exercised end-to-end. The host declares a static {@code @OnReload} counter; the number of
 * real {@code redefineClasses} calls is counted at the Instrumentation boundary.</p>
 */
public class FingerprintDiskHashProbe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static final String HOST_V1 = """
		package fp;
		import remold.annotation.OnReload;
		public class Host {
		    public static int reloads;
		    public Host() {
		        new Runnable() { public void run() { System.out.println("v1"); } };
		    }
		    @OnReload
		    public static void onReload() { reloads++; }
		}
		""";

	static final String HOST_V2 = """
		package fp;
		import remold.annotation.OnReload;
		public class Host {
		    public static int reloads;
		    public Host() {
		        new Runnable() { public void run() { System.out.println("added"); } };
		        new Runnable() { public void run() { System.out.println("v1"); } };
		    }
		    @OnReload
		    public static void onReload() { reloads++; }
		}
		""";

	static final String HOST_V3 = """
		package fp;
		import remold.annotation.OnReload;
		public class Host {
		    public static int reloads;
		    public Host() {
		        System.out.println("host-v3");
		        new Runnable() { public void run() { System.out.println("added"); } };
		        new Runnable() { public void run() { System.out.println("v1"); } };
		    }
		    @OnReload
		    public static void onReload() { reloads++; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== FingerprintDiskHashProbe: unchanged second round ===");
		Instrumentation real = RemoldAgent.getInstrumentation();
		if (real == null) {
			System.err.println("[FAIL] no instrumentation");
			System.exit(1);
		}
		RemoldAgent.INIT_FIX = true;
		RemoldAgent.ANON_ALIGN = true;

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("fp.Host", HOST_V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("fp.Host", HOST_V2));
		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("fp.Host", HOST_V3));
		check(v1.containsKey("fp.Host$1"), "v1 has one anonymous class (actual keys=" + v1.keySet() + ")");
		check(v2.containsKey("fp.Host$1") && v2.containsKey("fp.Host$2"),
			"v2 has two anonymous classes (actual keys=" + v2.keySet() + ")");

		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> host = Class.forName("fp.Host", true, loader);
		Class.forName("fp.Host$1", true, loader);
		check(intField(host, "reloads") == 0, "precondition: reload counter starts at 0");

		Path dir = Files.createTempDirectory("fp_disk_hash");
		Path hostFile = write(dir, "fp/Host.class", v2.get("fp.Host"));
		Path a1 = write(dir, "fp/Host$1.class", v2.get("fp.Host$1"));
		Path a2 = write(dir, "fp/Host$2.class", v2.get("fp.Host$2"));
		Set<Path> changed = new HashSet<>(Set.of(hostFile, a1, a2));

		RemoldAgent.bytecodeCache.put("fp.Host", v1.get("fp.Host"));
		RemoldAgent.bytecodeCache.put("fp.Host$1", v1.get("fp.Host$1"));
		RemoldAgent.sourceBytecodeCache.put("fp.Host", v1.get("fp.Host"));
		RemoldAgent.sourceBytecodeCache.put("fp.Host$1", v1.get("fp.Host$1"));

		RemoldAgent.Logger originalLogger = RemoldAgent.logger;
		boolean originalDebug = RemoldAgent.DEBUG;
		List<String> captured = new ArrayList<>();
		RemoldAgent.DEBUG = true;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { captured.add(msg); }
			public void info(String msg) { captured.add(msg); }
			public void warn(String msg) { captured.add(msg); }
			public void error(String msg) { captured.add(msg); }
			public void error(String msg, Throwable t) { captured.add(msg + " / " + t); }
		};
		AtomicInteger redefineCalls = new AtomicInteger();
		Instrumentation counting = counting(real, redefineCalls);
		Field instField = RemoldAgent.class.getDeclaredField("inst");
		instField.setAccessible(true);
		instField.set(null, counting);
		try {
			Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
			process.setAccessible(true);

			process.invoke(null, changed, real.getAllLoadedClasses());
			List<String> firstLog = new ArrayList<>(captured);
			captured.clear();
			int afterFirst = intField(host, "reloads");
			int callsAfterFirst = redefineCalls.get();
			Field diskField = RemoldAgent.class.getDeclaredField("fileDiskHashes");
			diskField.setAccessible(true);
			LongLongMap disk = (LongLongMap) diskField.get(null);
			long key = CRC64.hashString("fp.Host");
			long rawHash = CRC64.update(v2.get("fp.Host"));
			check(disk.get(key) == rawHash,
				"fileDiskHashes records the raw disk hash, not aligned bytes (disk="
				+ disk.get(key) + ", raw=" + rawHash + ")");
			check(afterFirst == 1, "first round fires @OnReload exactly once (actual=" + afterFirst + ")");
			check(callsAfterFirst > 0, "first round really redefined classes (actual=" + callsAfterFirst + ")");
			check(firstLog.stream().anyMatch(m -> m.contains("[ANON_ALIGN] Aligning anonymous classes")),
				"first round really ran anonymous-class alignment");

			process.invoke(null, changed, real.getAllLoadedClasses());
			List<String> secondLog = new ArrayList<>(captured);
			int afterSecond = intField(host, "reloads");
			int callsAfterSecond = redefineCalls.get();
			check(secondLog.stream().noneMatch(m -> m.contains("[ANON_ALIGN] Aligning anonymous classes")),
				"second unchanged round must not run anonymous-class alignment");
			check(afterSecond == 1,
				"second unchanged round must not fire @OnReload (actual=" + afterSecond + ")");
			check(callsAfterSecond == callsAfterFirst,
				"second unchanged round must not redefine (first=" + callsAfterFirst
				+ ", second=" + callsAfterSecond + ")");

			// A real source change must still take effect after the unchanged round.
			write(dir, "fp/Host.class", v3.get("fp.Host"));
			write(dir, "fp/Host$1.class", v3.get("fp.Host$1"));
			write(dir, "fp/Host$2.class", v3.get("fp.Host$2"));
			process.invoke(null, changed, real.getAllLoadedClasses());
			List<String> thirdLog = new ArrayList<>(captured);
			int afterThird = intField(host, "reloads");
			int callsAfterThird = redefineCalls.get();
			check(afterThird == 2, "a real source change still fires @OnReload (actual=" + afterThird + ")");
			check(callsAfterThird > callsAfterSecond,
				"a real source change still redefines (second=" + callsAfterSecond
				+ ", third=" + callsAfterThird + ")");
		} finally {
			instField.set(null, real);
			RemoldAgent.logger = originalLogger;
			RemoldAgent.DEBUG = originalDebug;
		}

		System.out.println("\n=== FINGERPRINT DISK HASH PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static Instrumentation counting(Instrumentation real, AtomicInteger calls) {
		return (Instrumentation) Proxy.newProxyInstance(
			FingerprintDiskHashProbe.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				if ("redefineClasses".equals(method.getName())) calls.incrementAndGet();
				try {
					return method.invoke(real, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			});
	}

	static Path write(Path root, String relative, byte[] bytes) throws Exception {
		Path p = root.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.write(p, bytes);
		return p;
	}

	static int intField(Class<?> type, String name) throws Exception {
		Field f = type.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(null);
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(FingerprintDiskHashProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}

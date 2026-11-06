package com.example.probe;

import net.fabricmc.api.ModInitializer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * C1+C2 joint reproduction: the entity-probe toggle
 * ({@code DynamicProfilerAPI.toggleEntityProbe(baseClass, method, true)}) retransforms every
 * loaded subclass of the base class. For a Mixin target that retransform enters
 * {@code AnnotationTransformer}'s external-redefine channel with <b>no</b> internal context, so
 * the C2 {@code captureOnly} guard does not apply: the already-woven classfile buffer is fed to
 * the Mixin bridge a second time.
 *
 * <p>Measured before/after the C2 commit with the same probe and sequence:</p>
 * <ul>
 *   <li>{@code bytecodeCache} SHA-256 of the target class,</li>
 *   <li>field table of the bytes the JVM actually has (read with
 *       {@code RemoldAgent.fetchBytecodeMemory}, an internal retransform that does not re-weave)
 *       — duplicate {@code remold$state}/{@code remold$useCount} fields show the double weave,</li>
 *   <li>the next real hot-swap's LayoutGate verdict.</li>
 * </ul>
 *
 * <p>Gated by {@code -Dremold.probe.c1c2=true}; driven by {@code c3/run-c1c2.sh}.</p>
 */
public final class C1C2Probe implements ModInitializer {

	private static final String TAG = "[C1C2] ";
	private static final String TARGET = "com.example.item.RemoldWandItem";
	private static final Path RUN = Path.of("run");

	private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

	private static PrintStream originalOut;
	private static Class<?> agent;
	private static Class<?> target;

	@Override
	public void onInitialize() {
		if (!Boolean.getBoolean("remold.probe.c1c2")) {
			return;
		}
		Thread thread = new Thread(C1C2Probe::run, "c1c2-probe");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		try {
			installTee();
			agent = Class.forName("remold.RemoldAgent");
			target = Class.forName(TARGET, true, C1C2Probe.class.getClassLoader());
			out("PHASE0 cache_sha=" + sha(appliedBytes()) + " live_sha=" + sha(liveBytes()));

			Class<?> api = Class.forName("remold.profiler.DynamicProfilerAPI");

			out("WAIT-TOGGLE");
			await("c1c2-toggle.trigger", 180_000);
			// Base = the Mixin target itself: the retransform set is exactly one already-woven
			// target, so the reproduction is deterministic (a broad Item-subclass batch also
			// retransforms ~200 vanilla Mixin targets and takes minutes). The code path under
			// test is the same one any base class takes.
			toggle(api, target, "self");
			sleep(3_000);
			measure("TOGGLE");
			out("TOGGLE-DONE");

			out("WAIT-HOTSWAP");
			await("c1c2-hotswap.trigger", 180_000);
			int successBefore = countCaptured("HotSwap successful");
			int rejectBefore = countCaptured("LAYOUT-REJECT") + countCaptured("REMOVED_FIELD")
				+ countCaptured("EXTERNAL-HOTSWAP") + countCaptured("redefine failed")
				+ countCaptured("Retransform failed");
			long deadline = System.currentTimeMillis() + 45_000;
			boolean success = false;
			boolean rejected = false;
			while (System.currentTimeMillis() < deadline) {
				success = countCaptured("HotSwap successful") > successBefore;
				rejected = countCaptured("LAYOUT-REJECT") + countCaptured("REMOVED_FIELD")
					+ countCaptured("EXTERNAL-HOTSWAP") + countCaptured("redefine failed")
					+ countCaptured("Retransform failed") > rejectBefore;
				if (success || rejected) break;
				sleep(500);
			}
			out("HOTSWAP success=" + success + " rejected=" + rejected);
			byte[] cacheAfter = appliedBytes();
			out("HOTSWAP cache_sha=" + sha(cacheAfter) + " cache_fields=" + fieldNames(cacheAfter));
			out("HOTSWAP cache_state_field_count=" + fieldCount(cacheAfter, "remold$state"));
			out("HOTSWAP cache_duplicate_fields=" + duplicateFields(cacheAfter));
			List<String> snapshot;
			synchronized (CAPTURED) {
				snapshot = new ArrayList<>(CAPTURED);
			}
			for (String line : snapshot) {
				if (line.contains("LAYOUT") || line.contains("REMOVED_FIELD")
					|| line.contains("EXTERNAL-HOTSWAP") || line.contains("HotSwap successful")
					|| line.contains("Retransform failed") || line.contains("redefine failed")
					|| line.contains("[MIXIN-DUP]")) {
					out("LOG " + line);
				}
			}
			out("DONE");
		} catch (Throwable t) {
			out("PROBE-ERROR " + t);
			t.printStackTrace(System.out);
			out("DONE");
		} finally {
			System.out.flush();
			Runtime.getRuntime().halt(0);
		}
	}

	private static void toggle(Class<?> api, Class<?> base, String label) {
		out("TOGGLE invoking toggleEntityProbe(" + base.getName() + ", \"use\", true) [" + label + "]");
		try {
			api.getMethod("toggleEntityProbe", Class.class, String.class, boolean.class)
				.invoke(null, base, "use", true);
			out("TOGGLE returned normally");
		} catch (Throwable t) {
			out("TOGGLE threw " + t);
		}
	}

	private static void measure(String phase) {
		try {
			byte[] cache = appliedBytes();
			out(phase + " cache_sha=" + sha(cache) + " cache_fields=" + fieldNames(cache));
			out(phase + " cache_state_field_count=" + fieldCount(cache, "remold$state")
				+ " cache_useCount_field_count=" + fieldCount(cache, "remold$useCount"));
			out(phase + " cache_duplicate_fields=" + duplicateFields(cache));
		} catch (Throwable t) {
			out(phase + " cache unavailable: " + t);
		}
		try {
			byte[] live = liveBytes();
			if (live == null) {
				out(phase + " fetchBytecodeMemory=null; falling back to direct capture");
				live = captureLive();
			}
			if (live == null) {
				out(phase + " live-bytes unavailable: both capture paths returned null");
				out(phase + " reflective fields (may be stale on this JBR)="
					+ java.util.Arrays.stream(target.getDeclaredFields()).map(Field::getName).toList());
			} else {
				out(phase + " live_sha=" + sha(live) + " live_fields=" + fieldNames(live));
				out(phase + " live_state_field_count=" + fieldCount(live, "remold$state")
					+ " live_useCount_field_count=" + fieldCount(live, "remold$useCount"));
				out(phase + " live_duplicate_fields=" + duplicateFields(live));
			}
		} catch (Throwable t) {
			out(phase + " live-bytes unavailable: " + t);
		}
	}

	/** Direct capture: same internal-retransform context, but with our own last-in-chain transformer. */
	private static byte[] captureLive() throws Exception {
		Object raw = agent.getMethod("getInstrumentation").invoke(null);
		if (!(raw instanceof java.lang.instrument.Instrumentation instrumentation)) return null;
		byte[][] cap = {null};
		java.lang.instrument.ClassFileTransformer capture = new java.lang.instrument.ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
				if (classBeingRedefined == target) cap[0] = classfileBuffer;
				return null;
			}
		};
		instrumentation.addTransformer(capture, true);
		out("CAPTURE-DEBUG modifiable=" + instrumentation.isModifiableClass(target)
			+ " retransformSupported=" + instrumentation.isRetransformClassesSupported());
		agent.getMethod("enterInternalRetransform", java.util.Collection.class)
			.invoke(null, java.util.Collections.singleton(target.getName()));
		try {
			instrumentation.retransformClasses(target);
			out("CAPTURE-DEBUG retransformClasses returned, captured=" + (cap[0] != null));
		} finally {
			agent.getMethod("exitInternalRedefine").invoke(null);
			instrumentation.removeTransformer(capture);
		}
		return cap[0];
	}

	// ------------------------------------------------------------------ measurements

	private static byte[] appliedBytes() throws Exception {
		Map<?, ?> cache = staticField(agent, "bytecodeCache", Map.class);
		Object bytes = cache.get(TARGET);
		if (!(bytes instanceof byte[] b)) throw new IllegalStateException("no bytecodeCache entry for " + TARGET);
		return b;
	}

	/** Current JVM bytes; {@code fetchBytecodeMemory} uses an internal retransform (no re-weave). */
	private static byte[] liveBytes() throws Exception {
		return (byte[]) agent.getMethod("fetchBytecodeMemory", Class.class).invoke(null, target);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static long fieldCount(byte[] bytes, String name) {
		return parse(bytes).fields.stream().filter(f -> f.name.equals(name)).count();
	}

	private static List<String> fieldNames(byte[] bytes) {
		List<String> names = new ArrayList<>();
		for (FieldNode f : parse(bytes).fields) names.add(f.name);
		return names;
	}

	private static List<String> duplicateFields(byte[] bytes) {
		Set<String> seen = new HashSet<>();
		List<String> dup = new ArrayList<>();
		for (FieldNode f : parse(bytes).fields) {
			if (!seen.add(f.name)) dup.add(f.name);
		}
		return dup;
	}

	private static String sha(byte[] bytes) throws Exception {
		if (bytes == null) return "null";
		byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : d) sb.append(String.format("%02x", b));
		return sb.substring(0, 16) + "(" + bytes.length + "B)";
	}

	// ------------------------------------------------------------------ plumbing

	@SuppressWarnings("unchecked")
	private static <T> T staticField(Class<?> type, String name, Class<T> fieldType) throws Exception {
		Field f = type.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(null);
	}

	private static void await(String triggerName, long timeoutMs) {
		Path trigger = RUN.resolve(triggerName);
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!Files.exists(trigger)) {
			if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timeout waiting for " + trigger);
			sleep(200);
		}
	}

	private static int countCaptured(String fragment) {
		synchronized (CAPTURED) {
			int n = 0;
			for (String line : CAPTURED) if (line.contains(fragment)) n++;
			return n;
		}
	}

	private static void installTee() {
		originalOut = System.out;
		System.setOut(new PrintStream(new OutputStream() {
			private final ByteArrayOutputStream line = new ByteArrayOutputStream();

			@Override
			public synchronized void write(int b) {
				line.write(b);
				if (b == '\n') flushLine();
			}

			@Override
			public synchronized void write(byte[] b, int off, int len) {
				for (int i = 0; i < len; i++) write(b[off + i]);
			}

			@Override
			public synchronized void flush() {
				if (line.size() > 0) flushLine();
				originalOut.flush();
			}

			private void flushLine() {
				byte[] data = line.toByteArray();
				line.reset();
				try {
					originalOut.write(data);
					originalOut.flush();
				} catch (Throwable ignored) {
				}
				CAPTURED.add(new String(data, StandardCharsets.UTF_8).trim());
			}
		}, true, StandardCharsets.UTF_8));
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void out(String msg) {
		System.out.println(TAG + msg);
	}
}

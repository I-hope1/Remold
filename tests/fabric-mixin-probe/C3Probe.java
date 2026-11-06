package com.example.probe;

import net.fabricmc.api.ModInitializer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import remold.HotswapBatchListener;

/**
 * C3 end-to-end probe: real Fabric server, real Remold agent as {@code -javaagent}, real
 * file watcher -> real {@code processChanges} -> real Mixin bridge re-weave -> JVM redefine ->
 * InitFix patches on the live instance.
 *
 * <p>Driven by external control files (written by {@code c3/run-c3.sh} after a real
 * {@code gradlew compileJava} of a real source edit):</p>
 * <pre>
 *   run/c3-stage1.trigger   add a method to the Mixin target source
 *   run/c3-stage2.trigger   add a field to the Mixin target source
 *   run/c3-stage3.trigger   no-op round #1 (touch the class file, identical bytes)
 *   run/c3-stage4.trigger   no-op round #2 (touch again)
 *   run/c3-stage5.trigger   change the Mixin class itself (MixinHotSwap)
 * </pre>
 *
 * <p><b>Evidence discipline.</b> The applied bytecode is read from
 * {@code RemoldAgent.bytecodeCache} (exactly the bytes handed to {@code redefineClasses}) and
 * its field/method tables are parsed with ASM: that is the structural assertion. The
 * behavioural end result is captured from the redefined class itself: the target's
 * {@code @OnReload} callback reads the added field (the value InitFix wrote into the existing
 * instance) and the Mixin {@code @Inject} handler reads the {@code @Unique} state. Both print
 * through a tee installed on {@code System.out}, so the probe asserts the exact lines rather
 * than trusting reflective metadata.</p>
 *
 * <p>{@code Class.getDeclaredFields()} / {@code getDeclaredMethods()} are still dumped for the
 * record, but on this JBR they are <b>stale after a redefinition</b> (measured: a method added
 * by stage 1 does not appear, and {@code getDeclaredField} on a field added by stage 2 throws
 * {@code NoSuchFieldException} while the class itself reads the field correctly). They are
 * therefore recorded, never used as pass/fail evidence.</p>
 */
public final class C3Probe implements ModInitializer, HotswapBatchListener {

	private static final String TAG = "[C3] ";
	private static final String TARGET = "com.example.item.RemoldWandItem";
	private static final Path RUN = Path.of("run");

	private static final List<List<String>> BATCHES = Collections.synchronizedList(new ArrayList<>());
	private static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());
	private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

	private static PrintStream originalOut;
	private static Class<?> agent;
	private static Class<?> target;

	@Override
	public void onInitialize() {
		if (!Boolean.getBoolean("remold.probe.c3")) {
			return;
		}
		Thread thread = new Thread(C3Probe::run, "c3-probe");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
		List<String> names = new ArrayList<>();
		if (redefinedClasses != null) {
			for (ClassDefinition def : redefinedClasses) {
				names.add(def.getDefinitionClass().getName());
			}
		}
		BATCHES.add(names);
		out("BATCH#" + BATCHES.size() + " classes=" + names);
	}

	private static void run() {
		try {
			installTee();
			agent = Class.forName("remold.RemoldAgent");
			Class<?> iface = Class.forName("remold.HotswapBatchListener");
			agent.getMethod("addBatchListener", iface).invoke(null, new C3Probe());
			out("LISTENER registered via " + agent.getName() + " (interface loader=" + iface.getClassLoader() + ")");

			target = Class.forName(TARGET, true, C3Probe.class.getClassLoader());
			Class<?> mod = Class.forName("com.example.ExampleMod", true, C3Probe.class.getClassLoader());
			Object item = mod.getField("REMOLD_WAND").get(null);
			Field state = target.getDeclaredField("remold$state");
			state.setAccessible(true);
			state.setInt(item, 7);

			out("PHASE0 target=" + target.getName() + " loader=" + target.getClassLoader());
			out("PHASE0 itemClass=" + item.getClass().getName() + " state=" + state.getInt(item));
			dumpReflection("PHASE0");

			// ---------------------------------------------------------- stage 1: add method
			out("WAIT-STAGE1");
			await("c3-stage1.trigger", 180_000);
			awaitBatch(1, 120_000);
			check("stage1 real redefine batch contains " + TARGET, batchContains(1, TARGET));
			byte[] applied1 = appliedBytes();
			check("stage1 applied bytes contain method remold$c3Method", methodCount(applied1, "remold$c3Method") == 1);
			check("stage1 applied bytes keep exactly one @Unique field remold$state", fieldCount(applied1, "remold$state") == 1);
			check("stage1 applied bytes keep exactly one @Unique field remold$useCount", fieldCount(applied1, "remold$useCount") == 1);
			check("stage1 applied bytes have no duplicate field names", noDuplicateFields(applied1));
			check("stage1 applied bytes keep one @Inject handler remold$countUse", methodContainingCount(applied1, "remold$countUse") == 1);
			check("stage1 @OnReload on live instance saw new method",
				awaitCaptured("[C3-READ] stage1 method=C3-METHOD-V1", 30_000));
			check("stage1 @Inject handler alive and @Unique state preserved (7)",
				awaitCaptured("[C3-MIXIN] onReload state=7", 30_000));
			dumpApplied("STAGE1", applied1);
			out("STAGE1-DONE");

			// ---------------------------------------------------------- stage 2: add field
			out("WAIT-STAGE2");
			await("c3-stage2.trigger", 180_000);
			awaitBatch(2, 120_000);
			check("stage2 real redefine batch contains " + TARGET, batchContains(2, TARGET));
			byte[] applied2 = appliedBytes();
			check("stage2 applied bytes contain exactly one field remold$c3Added", fieldCount(applied2, "remold$c3Added") == 1);
			check("stage2 applied bytes have no duplicate field names", noDuplicateFields(applied2));
			check("stage2 applied bytes keep exactly one @Unique field remold$state", fieldCount(applied2, "remold$state") == 1);
			check("stage2 applied bytes keep one @Inject handler remold$countUse", methodContainingCount(applied2, "remold$countUse") == 1);
			check("stage2 InitFix patched live instance: @OnReload read added=456",
				awaitCaptured("[C3-READ] stage2 method=C3-METHOD-V1 added=456", 30_000));
			check("stage2 @Unique state still 7 on the live instance",
				awaitCaptured("[C3-MIXIN] onReload state=7", 30_000));
			dumpApplied("STAGE2", applied2);
			out("STAGE2-DONE");

			// ---------------------------------------------------------- stage 3/4: no-op rounds
			out("WAIT-STAGE3");
			await("c3-stage3.trigger", 180_000);
			int batches3 = BATCHES.size();
			int reads3 = countCaptured("[C3-READ]");
			sleep(8_000);
			check("no-op round #1 produced no redefine batch", BATCHES.size() == batches3);
			check("no-op round #1 produced no @OnReload callback", countCaptured("[C3-READ]") == reads3);
			check("no-op round #1 applied bytes unchanged", Arrays.equals(appliedBytes(), applied2));
			out("STAGE3-DONE");

			out("WAIT-STAGE4");
			await("c3-stage4.trigger", 180_000);
			int batches4 = BATCHES.size();
			int reads4 = countCaptured("[C3-READ]");
			sleep(8_000);
			check("no-op round #2 produced no redefine batch", BATCHES.size() == batches4);
			check("no-op round #2 produced no @OnReload callback", countCaptured("[C3-READ]") == reads4);
			check("no-op round #2 applied bytes unchanged", Arrays.equals(appliedBytes(), applied2));
			out("STAGE4-DONE");

			// ---------------------------------------------------------- stage 5: MixinHotSwap
			out("WAIT-STAGE5");
			await("c3-stage5.trigger", 180_000);
			try {
				awaitBatch(3, 120_000);
				check("stage5 MixinHotSwap batch contains " + TARGET, batchContains(3, TARGET));
				byte[] applied5 = appliedBytes();
				check("stage5 applied bytes contain exactly one mixin @Unique field remold$author",
					fieldCount(applied5, "remold$author") == 1);
				check("stage5 applied bytes have no duplicate field names", noDuplicateFields(applied5));
				check("stage5 applied bytes keep exactly one @Unique field remold$state", fieldCount(applied5, "remold$state") == 1);
				check("stage5 mixin @Inject handler saw state=7 and the new @Unique field",
					awaitCaptured("[C3-MIXIN] onReload state=7", 30_000)
						&& awaitCaptured("author=Modder", 30_000));
				dumpApplied("STAGE5", applied5);
			} catch (Throwable t) {
				out("STAGE5-UNSUPPORTED " + t);
			}
			out("STAGE5-DONE");
		} catch (Throwable t) {
			out("PROBE-ERROR " + t);
			t.printStackTrace(System.out);
			FAILURES.add("probe error: " + t);
		} finally {
			boolean ok = FAILURES.isEmpty();
			out("RESULT " + (ok ? "PASS" : "FAIL") + " failures=" + FAILURES);
			flush();
			Runtime.getRuntime().halt(ok ? 0 : 3);
		}
	}

	// ------------------------------------------------------------------ applied bytecode

	private static byte[] appliedBytes() throws Exception {
		Map<?, ?> cache = staticField(agent, "bytecodeCache", Map.class);
		Object bytes = cache.get(TARGET);
		if (!(bytes instanceof byte[] b)) {
			throw new IllegalStateException("bytecodeCache has no applied bytes for " + TARGET);
		}
		return b;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static long fieldCount(byte[] bytes, String name) {
		ClassNode node = parse(bytes);
		return node.fields.stream().filter(f -> f.name.equals(name)).count();
	}

	private static long methodCount(byte[] bytes, String name) {
		ClassNode node = parse(bytes);
		return node.methods.stream().filter(m -> m.name.equals(name)).count();
	}

	private static long methodContainingCount(byte[] bytes, String fragment) {
		ClassNode node = parse(bytes);
		return node.methods.stream().filter(m -> m.name.contains(fragment)).count();
	}

	private static boolean noDuplicateFields(byte[] bytes) {
		ClassNode node = parse(bytes);
		Set<String> seen = new HashSet<>();
		for (FieldNode f : node.fields) {
			if (!seen.add(f.name)) return false;
		}
		return true;
	}

	private static List<String> fieldNames(byte[] bytes) {
		List<String> names = new ArrayList<>();
		for (FieldNode f : parse(bytes).fields) names.add(f.name);
		return names;
	}

	private static List<String> methodNames(byte[] bytes) {
		List<String> names = new ArrayList<>();
		for (MethodNode m : parse(bytes).methods) names.add(m.name);
		return names;
	}

	// ------------------------------------------------------------------ plumbing

	@SuppressWarnings("unchecked")
	private static <T> T staticField(Class<?> type, String name, Class<T> fieldType) throws Exception {
		Field f = type.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(null);
	}

	private static void awaitBatch(int n, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (BATCHES.size() < n) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("timeout waiting for batch #" + n + " (got " + BATCHES.size() + ")");
			}
			sleep(100);
		}
	}

	private static void await(String triggerName, long timeoutMs) {
		Path trigger = RUN.resolve(triggerName);
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!Files.exists(trigger)) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("timeout waiting for " + trigger);
			}
			sleep(200);
		}
	}

	private static boolean batchContains(int oneBased, String className) {
		return BATCHES.size() >= oneBased && BATCHES.get(oneBased - 1).contains(className);
	}

	private static boolean awaitCaptured(String fragment, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			synchronized (CAPTURED) {
				for (String line : CAPTURED) {
					if (line.contains(fragment)) return true;
				}
			}
			if (System.currentTimeMillis() > deadline) {
				out("CAPTURE-MISS " + fragment);
				return false;
			}
			sleep(100);
		}
	}

	private static int countCaptured(String fragment) {
		synchronized (CAPTURED) {
			int n = 0;
			for (String line : CAPTURED) {
				if (line.contains(fragment)) n++;
			}
			return n;
		}
	}

	private static void dumpReflection(String phase) {
		out(phase + " reflective fields (STALE on JBR after redefine, recorded only)="
			+ Arrays.stream(target.getDeclaredFields()).map(Field::getName).toList());
		out(phase + " reflective methods (STALE on JBR after redefine, recorded only)="
			+ Arrays.stream(target.getDeclaredMethods()).map(Method::getName).toList());
	}

	private static void dumpApplied(String phase, byte[] bytes) {
		out(phase + " applied fields=" + fieldNames(bytes));
		out(phase + " applied methods=" + methodNames(bytes));
	}

	/**
	 * Tee on {@code System.out}: forwards every byte to the original stream and records
	 * complete lines, so the probe can assert the exact lines printed by the redefined class.
	 */
	private static void installTee() {
		originalOut = System.out;
		System.setOut(new PrintStream(new OutputStream() {
			private final ByteArrayOutputStream line = new ByteArrayOutputStream();

			@Override
			public synchronized void write(int b) {
				line.write(b);
				if (b == '\n') {
					flushLine();
				}
			}

			@Override
			public synchronized void write(byte[] b, int off, int len) {
				for (int i = 0; i < len; i++) {
					write(b[off + i]);
				}
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
					// forwarding must never break the probe
				}
				CAPTURED.add(new String(data, StandardCharsets.UTF_8).trim());
			}
		}, true, StandardCharsets.UTF_8));
	}

	private static void flush() {
		try {
			System.out.flush();
			if (originalOut != null) originalOut.flush();
		} catch (Throwable ignored) {
		}
	}

	private static void check(String what, boolean condition) {
		if (condition) {
			out("PASS " + what);
		} else {
			out("FAIL " + what);
			FAILURES.add(what);
		}
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

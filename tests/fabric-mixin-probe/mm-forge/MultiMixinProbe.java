package com.example.examplemod.probe;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Item 2.5 Forge half: two independent mixin configs (multi_a priority 900, multi_b priority 1100) on
 * the same target class. Stages (user numbering):
 *   stage 1 baseline (both handlers + @Unique state + priority order + same-name @Unique layout)
 *   stage 2 MixinA hot update (MixinB must survive, run once, order unchanged)
 *   stage 3 MultiTarget hot update (target + both mixins effective)
 *   stage 4 both mixins updated in one redefine batch
 *   stage 5 final same-name @Unique field layout observation
 *
 * Forge runs upstream SpongePowered Mixin 0.8.5 (not the Fabric sponge-mixin fork), which merges two
 * cross-mixin @Unique fields that share a name into ONE target field. The expected stable layout is
 * exactly [uniqueA, sharedNameTag, uniqueB] at every stage: no split into an extra
 * {@code <sessionId>$...$sharedNameTag$0} field, no duplicates, no LAYOUT-REJECT. The merged
 * sharedNameTag is a single storage slot, so both handlers must observe the same value; per-mixin
 * value isolation is NOT expected on Forge (it is on Fabric).
 */
public final class MultiMixinProbe {
	static final String TARGET = "com.example.examplemod.multi.MultiTarget";
	/** Forge 0.8.5 merge semantics: cross-mixin same-name @Unique collapses into one field. */
	static final List<String> FORGE_FIELDS = List.of("uniqueA", "sharedNameTag", "uniqueB");
	static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());
	static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());

	static PrintStream originalOut;
	static Class<?> target;
	static Object instance;
	static Method fire;
	static Map<String, byte[]> bytecodeCache;
	static String baselineOrder;

	/** Called from ExampleMod's constructor (Forge). */
	public static void start() {
		if (!Boolean.getBoolean("remold.probe.mm")) return;
		registerMixinConfigs();
		Thread t = new Thread(MultiMixinProbe::run, "mm-probe");
		t.setDaemon(true);
		t.start();
	}

	static void registerMixinConfigs() {
		try {
			Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
			Object env = envClass.getMethod("getDefaultEnvironment").invoke(null);
			envClass.getMethod("addConfiguration", String.class).invoke(env, "multi_a.mixins.json");
			envClass.getMethod("addConfiguration", String.class).invoke(env, "multi_b.mixins.json");
			out("MIXIN-CONFIGS registered programmatically");
		} catch (Throwable t) {
			fail("mixin config registration failed: " + t);
		}
	}

	static void run() {
		try {
			installTee();
			Thread.sleep(3000);
			target = Class.forName(TARGET);
			instance = target.getConstructor().newInstance();
			fire = target.getMethod("fire");
			Class<?> agent = Class.forName("remold.RemoldAgent");
			bytecodeCache = (Map<String, byte[]>) agent.getField("bytecodeCache").get(null);

			// ---------------- stage 1 baseline ----------------
			String r = call();
			check("stage1 return T0", "T0".equals(r));
			check("stage1 A handler exactly once", count("[MM-A]") == 1);
			check("stage1 B handler exactly once", count("[MM-B]") == 1);
			baselineOrder = order();
			out("ORDER baseline=" + baselineOrder);
			check("stage1 order A,B", "A,B".equals(baselineOrder));
			check("stage1 probe can read the server log", logContains("MIXIN-CONFIGS registered programmatically"));
			int[] before = readUnique();
			check("stage1 uniqueA/uniqueB visible", before[0] >= 0 && before[1] >= 0);
			setUnique(111, 222);
			check("stage1 unique fields writable", readUnique()[0] == 111 && readUnique()[1] == 222);
			expectForgeLayout("baseline");
			call();
			checkHandlerValues("stage1");
			out("WAIT-STAGE1");

			// ---------------- stage 2: MixinA hot update only ----------------
			awaitTrigger("mm-stage2.trigger");
			pollUntil(() -> count("[MM-A] v2") == 1 && count("[MM-B] v1") == 1, 60_000, "stage2 mixinA v2 + mixinB v1");
			check("stage2 A updated", count("[MM-A] v2") == 1);
			check("stage2 B still alive exactly once", count("[MM-B] v1") == 1);
			check("stage2 order unchanged", baselineOrder.equals(order()));
			check("stage2 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			expectForgeLayout("stage2");
			call();
			checkHandlerValues("stage2");
			out("STAGE2-DONE");

			// ---------------- stage 3: MultiTarget hot update ----------------
			awaitTrigger("mm-stage3.trigger");
			pollUntil(() -> "T1".equals(call()) && count("[MM-A] v2") == 1 && count("[MM-B] v1") == 1,
				60_000, "stage3 target v2 + both mixins");
			check("stage3 target updated", "T1".equals(call()));
			check("stage3 A once", count("[MM-A] v2") == 1);
			check("stage3 B once", count("[MM-B] v1") == 1);
			check("stage3 order unchanged", baselineOrder.equals(order()));
			check("stage3 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			expectForgeLayout("stage3");
			call();
			checkHandlerValues("stage3");
			out("STAGE3-DONE");

			// ---------------- stage 4: both mixins in one batch ----------------
			awaitTrigger("mm-stage4.trigger");
			pollUntil(() -> count("[MM-A] v3") == 1 && count("[MM-B] v2") == 1, 60_000,
				"stage4 both mixins updated in one batch");
			check("stage4 A updated", count("[MM-A] v3") == 1);
			check("stage4 B updated", count("[MM-B] v2") == 1);
			check("stage4 order unchanged", baselineOrder.equals(order()));
			check("stage4 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			expectForgeLayout("stage4");
			call();
			checkHandlerValues("stage4");
			out("STAGE4-DONE");

			// ---------------- stage 5: final layout observation ----------------
			awaitTrigger("mm-stage5.trigger");
			pollUntil(() -> count("[MM-A] v3") == 1 && count("[MM-B] v2") == 1, 30_000, "stage5 final call");
			expectForgeLayout("stage5");
			check("stage5 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			call();
			checkHandlerValues("stage5");
			out("STAGE5-DONE");
		} catch (Throwable t) {
			out("PROBE-ERROR " + t);
			t.printStackTrace(originalOut);
			fail("probe error: " + t);
		} finally {
			out("RESULT " + (FAILURES.isEmpty() ? "PASS" : "FAIL") + " failures=" + FAILURES);
			if (!FAILURES.isEmpty()) System.exit(3);
		}
	}

	static String call() {
		try {
			clear();
			Object r = fire.invoke(instance);
			return String.valueOf(r);
		} catch (Throwable t) {
			fail("fire() failed: " + t);
			return "<error>";
		}
	}

	static void pollUntil(BooleanSupplier ok, long timeoutMs, String label) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			call();
			if (ok.getAsBoolean()) return;
			Thread.sleep(500);
		}
		fail("timeout waiting for " + label);
	}

	static int count(String needle) {
		int n = 0;
		synchronized (CAPTURED) {
			for (String s : CAPTURED) if (s.contains(needle)) n++;
		}
		return n;
	}

	static String order() {
		int a = -1, b = -1;
		synchronized (CAPTURED) {
			for (int i = 0; i < CAPTURED.size(); i++) {
				if (a < 0 && CAPTURED.get(i).contains("[MM-A]")) a = i;
				if (b < 0 && CAPTURED.get(i).contains("[MM-B]")) b = i;
			}
		}
		return a >= 0 && b >= 0 ? (a < b ? "A,B" : "B,A") : "missing";
	}

	static void clear() {
		CAPTURED.clear();
	}

	/** Integer printed by a mixin handler line, e.g. "[MM-A] v1 uniqueA=111 shared=2222". */
	static int handlerInt(String prefix, String key) {
		String needle = key + "=";
		synchronized (CAPTURED) {
			for (String s : CAPTURED) {
				if (!s.contains(prefix)) continue;
				int i = s.indexOf(needle);
				if (i < 0) continue;
				int j = i + needle.length();
				int k = j;
				while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
				if (k > j) return Integer.parseInt(s.substring(j, k));
			}
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * End-to-end read-back through each mixin's own injected handler (the observable business result),
	 * not through reflection: uniqueA=111 / uniqueB=222 must survive every hot update, and both
	 * handlers must observe the SAME merged sharedNameTag value on Forge 0.8.5.
	 */
	static void checkHandlerValues(String phase) {
		check(phase + " A handler reads uniqueA=111", handlerInt("[MM-A]", "uniqueA") == 111);
		check(phase + " B handler reads uniqueB=222", handlerInt("[MM-B]", "uniqueB") == 222);
		int sharedA = handlerInt("[MM-A]", "shared");
		int sharedB = handlerInt("[MM-B]", "shared");
		check(phase + " both handlers read the same merged sharedNameTag",
			sharedA != Integer.MIN_VALUE && sharedA == sharedB);
	}

	static int[] readUnique() {
		try {
			return new int[]{readInt("uniqueA"), readInt("uniqueB")};
		} catch (Throwable t) {
			return new int[]{-1, -1};
		}
	}

	static int readInt(String name) throws Exception {
		Field f = target.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(instance);
	}

	static void setUnique(int a, int b) {
		try {
			Field fa = target.getDeclaredField("uniqueA");
			fa.setAccessible(true);
			fa.setInt(instance, a);
			Field fb = target.getDeclaredField("uniqueB");
			fb.setAccessible(true);
			fb.setInt(instance, b);
		} catch (Throwable t) {
			fail("setUnique failed: " + t);
		}
	}

	static int countFieldsContaining(String part) {
		int n = 0;
		for (String f : appliedFieldNames()) if (f.contains(part)) n++;
		return n;
	}

	/**
	 * Forge upstream Mixin 0.8.5 layout invariant: exactly [uniqueA, sharedNameTag, uniqueB] after
	 * every stage. A split (extra {@code $sharedNameTag$0} entry), a duplicate, or any layout
	 * rejection fails the probe.
	 */
	static void expectForgeLayout(String phase) {
		List<String> names = appliedFieldNames();
		out("FIELDS " + phase + "=" + names);
		check(phase + " FIELDS exactly " + FORGE_FIELDS, names.equals(FORGE_FIELDS));
		check(phase + " no duplicate field names", names.size() == new HashSet<>(names).size());
		check(phase + " same-name @Unique merged into one field", countFieldsContaining("sharedNameTag") == 1);
		check(phase + " no layout rejection", !logContainsLayoutReject());
	}

	static List<String> appliedFieldNames() {
		List<String> names = new ArrayList<>();
		try {
			byte[] bytes = bytecodeCache == null ? null : bytecodeCache.get(TARGET);
			if (bytes == null) return names;
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
				                                                  String signature, Object value) {
					names.add(name);
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable t) {
			fail("appliedFieldNames failed: " + t);
		}
		return names;
	}

	/**
	 * The Forge server's working directory is {@code run/}, so the driver's {@code run/mm.log} is
	 * just {@code mm.log} from the probe's point of view; Fabric probes run from the project root and
	 * see {@code run/mm.log}. Read whichever exists (both if present) so the LAYOUT-REJECT check is
	 * live instead of silently reading a missing file.
	 */
	static boolean logContains(String needle) {
		for (Path p : new Path[]{Path.of("mm.log"), Path.of("run", "mm.log")}) {
			try {
				if (Files.exists(p) && Files.readString(p).contains(needle)) return true;
			} catch (Throwable ignored) {
			}
		}
		return false;
	}

	/**
	 * Real rejection markers only: {@code [LAYOUT-REJECT] class: ...} or the {@code [HOTSWAP-SKIPPED]}
	 * summary line {@code ... <- LAYOUT-REJECT: ...}. Searching for the bare token would match the
	 * probe's own "[MM] PASS ... no layout rejection" lines once they are in the log.
	 */
	static boolean logContainsLayoutReject() {
		return logContains("[LAYOUT-REJECT]") || logContains("LAYOUT-REJECT:")
			|| logContains("LAYOUT-REJECT ");
	}

	/** Trigger files: driver writes both run/&lt;name&gt; and run/run/&lt;name&gt; (Forge cwd is run/). */
	static void awaitTrigger(String name) throws InterruptedException {
		Path a = Path.of("run", name);
		Path b = Path.of(name);
		long deadline = System.currentTimeMillis() + 120_000;
		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(a) || Files.exists(b)) return;
			Thread.sleep(250);
		}
		fail("timeout waiting for trigger " + name);
	}

	static void installTee() {
		originalOut = System.out;
		System.setOut(new PrintStream(new OutputStream() {
			final StringBuilder line = new StringBuilder();

			@Override
			public void write(int b) {
				originalOut.write(b);
				if (b == '\n') {
					CAPTURED.add(line.toString());
					line.setLength(0);
				} else {
					line.append((char) b);
				}
			}

			@Override
			public void write(byte[] b, int off, int len) {
				for (int i = off; i < off + len; i++) write(b[i]);
			}

			@Override
			public void flush() {
				originalOut.flush();
			}
		}, true, StandardCharsets.UTF_8));
	}

	static void out(String s) {
		System.out.println("[MM] " + s);
	}

	static void check(String msg, boolean ok) {
		if (ok) System.out.println("[MM] PASS " + msg);
		else fail(msg);
	}

	static void fail(String msg) {
		FAILURES.add(msg);
		System.out.println("[MM] FAIL " + msg);
	}
}

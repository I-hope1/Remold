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
 * Item 2.5: two independent mixin configs (multi_a priority 900, multi_b priority 1100) on the
 * same target class. Stages (user numbering):
 *   stage 1 baseline (both handlers + @Unique state + priority order + same-name @Unique fields)
 *   stage 2 MixinA-only hot update (MixinB must survive, run once, order unchanged)
 *   stage 3 MultiTarget hot update (target + both mixins effective)
 *   stage 4 both mixins updated in one redefine batch
 *   stage 5 same-name @Unique field drift observation (applied bytecode field names)
 */
public final class MultiMixinProbe {
	static final String TARGET = "com.example.examplemod.multi.MultiTarget";
	static final Path RUN = Path.of("run");
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
			int[] before = readUnique();
			check("stage1 uniqueA/uniqueB visible", before[0] >= 0 && before[1] >= 0);
			setUnique(111, 222);
			check("stage1 unique fields writable", readUnique()[0] == 111 && readUnique()[1] == 222);
			List<String> shared = sharedFieldNames();
			check("stage1 two same-name @Unique fields merged", shared.size() == 2);
			setSharedIsolation(shared);
			call();
			check("stage1 A shared value isolated", hasLine("[MM-A]", "shared=1111"));
			check("stage1 B shared value isolated", hasLine("[MM-B]", "shared=2222"));
			dumpAppliedFields("baseline");
			out("WAIT-STAGE1");

			// ---------------- stage 2: MixinA hot update only ----------------
			awaitTrigger("mm-stage2.trigger");
			pollUntil(() -> count("[MM-A] v2") == 1 && count("[MM-B] v1") == 1, 60_000, "stage2 mixinA v2 + mixinB v1");
			check("stage2 A updated", count("[MM-A] v2") == 1);
			check("stage2 B still alive exactly once", count("[MM-B] v1") == 1);
			check("stage2 order unchanged", baselineOrder.equals(order()));
			check("stage2 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			call();
			check("stage2 A shared value isolated", hasLine("[MM-A]", "shared=1111"));
			check("stage2 B shared value isolated", hasLine("[MM-B]", "shared=2222"));
			dumpAppliedFields("stage2");
			check("stage2 no LAYOUT-REJECT", !logContains("LAYOUT-REJECT"));
			out("STAGE2-DONE");

			// ---------------- stage 3: MultiTarget hot update ----------------
			awaitTrigger("mm-stage3.trigger");
			pollUntil(() -> "T1".equals(call()) && count("[MM-A] v2") == 1 && count("[MM-B] v1") == 1,
				60_000, "stage3 target v2 + both mixins");
			check("stage3 target updated", "T1".equals(call()));
			check("stage3 A once", count("[MM-A] v2") == 1);
			check("stage3 B once", count("[MM-B] v1") == 1);
			check("stage3 order unchanged", baselineOrder.equals(order()));
			call();
			check("stage3 A shared value isolated", hasLine("[MM-A]", "shared=1111"));
			check("stage3 B shared value isolated", hasLine("[MM-B]", "shared=2222"));
			dumpAppliedFields("stage3");
			out("STAGE3-DONE");

			// ---------------- stage 4: both mixins in one batch ----------------
			awaitTrigger("mm-stage4.trigger");
			pollUntil(() -> count("[MM-A] v3") == 1 && count("[MM-B] v2") == 1, 60_000,
				"stage4 both mixins updated in one batch");
			check("stage4 A updated", count("[MM-A] v3") == 1);
			check("stage4 B updated", count("[MM-B] v2") == 1);
			check("stage4 order unchanged", baselineOrder.equals(order()));
			check("stage4 uniqueA/uniqueB preserved", readUnique()[0] == 111 && readUnique()[1] == 222);
			call();
			check("stage4 A shared value isolated", hasLine("[MM-A]", "shared=1111"));
			check("stage4 B shared value isolated", hasLine("[MM-B]", "shared=2222"));
			dumpAppliedFields("stage4");
			out("STAGE4-DONE");

			// ---------------- stage 5: same-name @Unique drift ----------------
			awaitTrigger("mm-stage5.trigger");
			pollUntil(() -> count("[MM-A] v3") == 1 && count("[MM-B] v2") == 1, 30_000, "stage5 final call");
			List<String> fields = appliedFieldNames();
			out("FIELDS final=" + fields);
			check("stage5 no duplicate field names", fields.size() == new HashSet<>(fields).size());
			check("stage5 two sharedNameTag fields survive", countFieldsContaining("sharedNameTag") == 2);
			call();
			check("stage5 A shared value isolated", hasLine("[MM-A]", "shared=1111"));
			check("stage5 B shared value isolated", hasLine("[MM-B]", "shared=2222"));
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

	static List<String> sharedFieldNames() {
		List<String> names = new ArrayList<>();
		for (String f : appliedFieldNames()) if (f.contains("sharedNameTag")) names.add(f);
		return names;
	}

	/** A's field initializer is 100, B's is 200; write distinct sentinels through each name. */
	static void setSharedIsolation(List<String> names) {
		try {
			for (String n : names) {
				Field f = target.getDeclaredField(n);
				f.setAccessible(true);
				int v = f.getInt(instance);
				if (v == 100) f.setInt(instance, 1111);
				else if (v == 200) f.setInt(instance, 2222);
			}
		} catch (Throwable t) {
			fail("setSharedIsolation failed: " + t);
		}
	}

	static boolean hasLine(String prefix, String needle) {
		synchronized (CAPTURED) {
			for (String s : CAPTURED) if (s.contains(prefix) && s.contains(needle)) return true;
		}
		return false;
	}

	static int countFieldsContaining(String part) {
		int n = 0;
		for (String f : appliedFieldNames()) if (f.contains(part)) n++;
		return n;
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

	static void dumpAppliedFields(String phase) {
		List<String> names = appliedFieldNames();
		out("FIELDS " + phase + "=" + names);
		check(phase + " no duplicate applied field names",
			names.size() == new HashSet<>(names).size());
	}

	static boolean logContains(String needle) {
		try {
			String log = Files.readString(RUN.resolve("mm.log"));
			return log.contains(needle);
		} catch (Throwable t) {
			return false;
		}
	}

	static void awaitTrigger(String name) throws InterruptedException {
		Path p = RUN.resolve(name);
		long deadline = System.currentTimeMillis() + 120_000;
		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(p)) return;
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

package com.example.probe;

import net.fabricmc.api.ModInitializer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real Fabric/Mixin probe: run inside a real Fabric 1.21.1 Knot (server or client) with the
 * real Remold agent + plugin-mixin attached. It answers the retransform/fetchOriginal/
 * LayoutGate/@Unique-reset questions with SHA-256 bytes, not with a fake weaver.
 *
 * <p>Enable with {@code -Dremold.probe=true}. Everything is reflection-based so this source
 * compiles without Remold on the classpath; the agent jar is appended to the bootstrap search
 * by {@code RemoldAgent.premain}.</p>
 */
public final class MixinProbe implements ModInitializer {

	private static final String TAG = "[MIXIN-PROBE] ";
	private static final String TARGET = "com.example.item.RemoldWandItem";
	private static final String STATE_FIELD = "remold$state";
	private static final String STATE_INIT = "123";
	private static final String HANDLER = "remold$countUse";

	private static final Class<?> AGENT = agentClass();

	@Override
	public void onInitialize() {
		if (!Boolean.getBoolean("remold.probe")) {
			return;
		}
		out("=== real Fabric Mixin probe start ===");
		try {
			runAll();
		} catch (Throwable t) {
			out("PROBE-ERROR " + t);
			t.printStackTrace(System.out);
		} finally {
			System.out.flush();
			// The probe runs in the mod initializer: no world/GUI is needed.
			Runtime.getRuntime().halt(0);
		}
	}

	private static void runAll() throws Exception {
		Class<?> target = Class.forName(TARGET, true, MixinProbe.class.getClassLoader());
		Object item = Class.forName("com.example.ExampleMod").getField("REMOLD_WAND").get(null);

		out("target=" + target.getName() + " loader=" + target.getClassLoader());
		out("agentInst=" + (callStatic(AGENT, "getInstrumentation", new Class<?>[0]) != null));

		if ("reset".equals(System.getProperty("remold.probe.mode"))) {
			runReset(target, item);
			return;
		}
		if ("baseline".equals(System.getProperty("remold.probe.mode"))) {
			runBaseline(target);
			return;
		}

		byte[] rawResource = resourceBytes(target);
		byte[] disk = (byte[]) callStatic(AGENT, "findClassBytesOnDisk", new Class<?>[]{Class.class}, target);
		byte[] original = (byte[]) callStatic(AGENT, "fetchOriginalBytecode", new Class<?>[]{Class.class}, target);
		byte[] runtime = (byte[]) callStatic(AGENT, "fetchBytecodeMemory", new Class<?>[]{Class.class}, target);
		byte[] cache = (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET);
		byte[] source = (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET);
		byte[] sourceBytes = rawResource != null ? rawResource : disk;
		byte[] mixed = mixinTransform(sourceBytes);
		byte[] mixedFromDisk = mixinTransform(disk);

		report("raw_resource", rawResource);
		report("disk_watchdir", disk);
		report("fetchOriginalBytecode", original);
		report("retransform_input(classfileBuffer)", runtime);
		report("bytecodeCache", cache);
		report("sourceBytecodeCache", source);
		report("mixinTransform(raw_resource)", mixed);
		report("mixinTransform(disk)", mixedFromDisk);

		boolean inputHasMixin = runtime != null && contains(runtime, STATE_FIELD) && contains(runtime, HANDLER);
		boolean rawHasMixin = rawResource != null && contains(rawResource, STATE_FIELD);
		out("Q1 retransform input contains Mixin members = " + inputHasMixin
			+ " (raw contains? " + rawHasMixin
			+ ", byte-equal to bytecodeCache? " + Arrays.equals(runtime, cache)
			+ ", byte-equal to single weave? " + Arrays.equals(runtime, mixed) + ")");

		boolean fetchOriginalRaw = original != null && rawResource != null && Arrays.equals(original, rawResource);
		out("Q2 fetchOriginalBytecode is pre-weave = " + fetchOriginalRaw
			+ " (original==raw? " + fetchOriginalRaw + ", original==null? " + (original == null) + ")");

		List<String> diffInputSingle = changedFields(runtime, mixed);
		out("H diff(retransform_input, single_weave) = " + diffInputSingle
			+ " gate=" + gateVerdict(diffInputSingle));

		// ---- layout gate after a forced baseline capture ----
		// retransformLoaded skips classes already present in bytecodeCache; remove the entry to
		// force the fallback path (the same path a runtime agent attach/UI retransform uses for
		// classes not cached yet).
		staticField(AGENT, "bytecodeCache", Map.class).remove(TARGET);
		callStatic(AGENT, "retransformLoaded", new Class<?>[]{Class[].class}, (Object) new Class<?>[]{target});
		byte[] baseline = (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET);
		byte[] baselineSource = (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET);
		report("baseline_after_retransformLoaded", baseline);
		report("source_after_retransformLoaded", baselineSource);

		List<String> diffAgainstRaw = changedFields(baseline, sourceBytes);
		List<String> diffAgainstWoven = changedFields(baseline, mixed);
		out("Q3 diff(baseline, raw).changedFields = " + diffAgainstRaw);
		out("Q3 diff(baseline, woven).changedFields = " + diffAgainstWoven);
		out("Q3 gate(baseline, raw) = " + gateVerdict(diffAgainstRaw));
		out("Q3 gate(baseline, woven) = " + gateVerdict(diffAgainstWoven));

		// ---- rollback semantics: only sourceBytecodeCache is restored ----
		Map<String, byte[]> sourceBefore = new HashMap<>();
		sourceBefore.put(TARGET, sourceBytes);
		callStatic(AGENT, "rollbackSourceCache",
			new Class<?>[]{Map.class, Set.class, Set.class}, sourceBefore, Set.of(), Set.of(TARGET));
		report("after_rollback_sourceBytecodeCache", (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET));
		report("after_rollback_bytecodeCache", (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET));
		report("after_rollback_fetchOriginal", (byte[]) callStatic(AGENT,
			"fetchOriginalBytecode", new Class<?>[]{Class.class}, target));
		out("=== real Fabric Mixin probe end ===");
	}

	private static void runBaseline(Class<?> target) throws Exception {
		byte[] raw = resourceBytes(target);
		byte[] disk = (byte[]) callStatic(AGENT, "findClassBytesOnDisk", new Class<?>[]{Class.class}, target);
		byte[] runtime = (byte[]) callStatic(AGENT, "fetchBytecodeMemory", new Class<?>[]{Class.class}, target);
		byte[] cache = (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET);
		byte[] source = (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET);
		report("B_raw", raw);
		report("B_disk", disk);
		report("B_retransform_input", runtime);
		report("B_cache_before", cache);
		report("B_source_before", source);

		// clean baseline capture: no prior mixinTransform call in this process
		staticField(AGENT, "bytecodeCache", Map.class).remove(TARGET);
		callStatic(AGENT, "retransformLoaded", new Class<?>[]{Class[].class}, (Object) new Class<?>[]{target});
		byte[] baseline = (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET);
		byte[] sourceAfter = (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET);
		report("B_baseline_after_retransformLoaded", baseline);
		report("B_source_after_retransformLoaded", sourceAfter);

		byte[] woven = mixinTransform(raw);
		report("B_mixinTransform_raw", woven);
		List<String> dInput = changedFields(runtime, woven);
		List<String> dBaselineRaw = changedFields(baseline, raw);
		List<String> dBaselineWoven = changedFields(baseline, woven);
		out("B diff(retransform_input, woven) = " + dInput + " gate=" + gateVerdict(dInput));
		out("B diff(baseline, raw) = " + dBaselineRaw + " gate=" + gateVerdict(dBaselineRaw));
		out("B diff(baseline, woven) = " + dBaselineWoven + " gate=" + gateVerdict(dBaselineWoven));

		Map<String, byte[]> sourceBefore = new HashMap<>();
		sourceBefore.put(TARGET, raw);
		callStatic(AGENT, "rollbackSourceCache",
			new Class<?>[]{Map.class, Set.class, Set.class}, sourceBefore, Set.of(), Set.of(TARGET));
		report("B_after_rollback_to_raw_source", (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET));
		report("B_after_rollback_cache", (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET));
		report("B_after_rollback_fetchOriginal", (byte[]) callStatic(AGENT,
			"fetchOriginalBytecode", new Class<?>[]{Class.class}, target));

		// second rollback: restore the pre-capture woven source; fetchOriginal must NOT follow it
		Map<String, byte[]> prevSource = new HashMap<>();
		prevSource.put(TARGET, source);
		callStatic(AGENT, "rollbackSourceCache",
			new Class<?>[]{Map.class, Set.class, Set.class}, prevSource, Set.of(), Set.of(TARGET));
		report("B_after_rollback_to_prev_source", (byte[]) staticField(AGENT, "sourceBytecodeCache", Map.class).get(TARGET));
		report("B_after_rollback_to_prev_fetchOriginal", (byte[]) callStatic(AGENT,
			"fetchOriginalBytecode", new Class<?>[]{Class.class}, target));
	}

	private static void runReset(Class<?> target, Object item) throws Exception {
		byte[] sourceBytes = resourceBytes(target);
		if (sourceBytes == null) sourceBytes = (byte[]) callStatic(AGENT, "findClassBytesOnDisk",
			new Class<?>[]{Class.class}, target);
		report("Q4 raw_source", sourceBytes);

		Field state = target.getDeclaredField(STATE_FIELD);
		state.setAccessible(true);
		int before = state.getInt(item);
		int set = Integer.getInteger("remold.probe.state", 7);
		state.setInt(item, set);
		out("Q4 stateful @Unique " + STATE_FIELD + " before fallback redefine: initial="
			+ before + ", set=" + state.getInt(item));

		staticField(AGENT, "bytecodeCache", Map.class).remove(TARGET);
		byte[] fallback = (byte[]) callStatic(AGENT, "fetchOriginalBytecode", new Class<?>[]{Class.class}, target);
		report("Q4 fallback_baseline(fetchOriginal)", fallback);
		boolean ok = (Boolean) callStatic(AGENT, "redefineDynamic",
			new Class<?>[]{Class.class, byte[].class}, target, sourceBytes);
		int after = state.getInt(item);
		report("Q4_runtime_bytecodeCache_after", (byte[]) staticField(AGENT, "bytecodeCache", Map.class).get(TARGET));
		out("Q4 redefineDynamic(ok=" + ok + ") state after=" + after
			+ " (initializer=" + STATE_INIT + ", set-before=" + set + ")");

		// ---- completely missing baseline: fail-closed ----
		staticField(AGENT, "bytecodeCache", Map.class).remove(TARGET);
		staticField(AGENT, "sourceBytecodeCache", Map.class).remove(TARGET);
		if (callStatic(AGENT, "fetchOriginalBytecode", new Class<?>[]{Class.class}, target) == null) {
			boolean none = (Boolean) callStatic(AGENT, "redefineDynamic",
				new Class<?>[]{Class.class, byte[].class}, target, sourceBytes);
			out("Q4 no-baseline-at-all redefineDynamic(ok=" + none + ") state=" + state.getInt(item));
		} else {
			out("Q4 no-baseline-at-all not reachable: fetchOriginalBytecode returned a resource");
		}
	}

	// ------------------------------------------------------------------ helpers

	private static Class<?> agentClass() {
		try {
			return Class.forName("remold.RemoldAgent");
		} catch (Throwable t) {
			return null;
		}
	}

	private static void report(String label, byte[] bytes) {
		if (bytes == null) {
			out(label + ": null");
			return;
		}
		try {
			Path dir = Path.of("build", "probe-dumps");
			Files.createDirectories(dir);
			Files.write(dir.resolve(label.replaceAll("[^A-Za-z0-9_.-]", "_") + ".class"), bytes);
		} catch (Throwable t) {
			out("dump " + label + " failed: " + t);
		}
		out(label + ": len=" + bytes.length
			+ " sha256=" + sha256Hex(bytes)
			+ " has_" + STATE_FIELD + "=" + contains(bytes, STATE_FIELD)
			+ " has_" + HANDLER + "=" + contains(bytes, HANDLER));
	}

	private static byte[] resourceBytes(Class<?> type) throws Exception {
		try (var in = type.getResourceAsStream("/" + TARGET.replace('.', '/') + ".class")) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static byte[] mixinTransform(byte[] raw) {
		if (raw == null) return null;
		try {
			Class<?> reweaver = Class.forName("remold.mixin.MixinReWeaver");
			Method m = reweaver.getMethod("reweave", String.class, byte[].class);
			return (byte[]) m.invoke(null, TARGET, raw);
		} catch (Throwable ignored) {
			// plugin class may not be visible from the Knot loader; use the real Mixin API directly
		}
		try {
			Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
			Object env = envClass.getMethod("getCurrentEnvironment").invoke(null);
			if (env == null) env = envClass.getMethod("getDefaultEnvironment").invoke(null);
			Object transformer = env.getClass().getMethod("getActiveTransformer").invoke(env);
			Class<?> itf = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer");
			Method m = itf.getMethod("transformClassBytes", String.class, String.class, byte[].class);
			return (byte[]) m.invoke(transformer, TARGET, TARGET, raw);
		} catch (Throwable t) {
			out("mixinTransform FAILED: " + t);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> changedFields(byte[] oldBytes, byte[] newBytes) throws Exception {
		if (oldBytes == null || newBytes == null) return List.of("<no-bytes>");
		Class<?> cdu = Class.forName("remold.ClassDiffUtil");
		Object diff = cdu.getMethod("diff", byte[].class, byte[].class).invoke(null, oldBytes, newBytes);
		Field f = diff.getClass().getField("changedFields");
		return (List<String>) f.get(diff);
	}

	private static String gateVerdict(List<String> changed) throws Exception {
		Class<?> gate = Class.forName("remold.LayoutGate");
		Object result = gate.getMethod("checkChangedFields", List.class).invoke(null, changed);
		Object verdict = result.getClass().getMethod("verdict").invoke(result);
		return String.valueOf(verdict);
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sha256Hex(byte[] bytes) {
		byte[] d = sha256(bytes);
		StringBuilder sb = new StringBuilder(d.length * 2);
		for (byte b : d) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private static boolean contains(byte[] haystack, String needle) {
		byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i <= haystack.length - n.length; i++) {
			for (int j = 0; j < n.length; j++) {
				if (haystack[i + j] != n[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static Object callStatic(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
		Method m = type.getDeclaredMethod(name, types);
		m.setAccessible(true);
		try {
			return m.invoke(null, args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception ex) throw ex;
			throw e;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T staticField(Class<?> type, String name, Class<T> fieldType) throws Exception {
		Field f = type.getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(null);
	}

	private static void out(String msg) {
		System.out.println(TAG + msg);
	}
}

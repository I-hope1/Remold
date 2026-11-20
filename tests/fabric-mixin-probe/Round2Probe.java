package com.example.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.fabricmc.api.ModInitializer;

/**
 * Round-2 probe: does a runtime {@code mixins.json} change republish the target index?
 *
 * <p>Scenario A (primary): {@code LateTargetMixin} is compiled and loadable but absent from the
 * config. The probe rewrites the runtime copy of {@code example_mod.mixins.json} to list it in
 * {@code mixins}, {@code client} and {@code server}, then polls {@code MixinTargetIndex.stamp()}
 * to see whether the production config-differ path republishes the index and whether
 * {@code LateTarget} flips from {@code NOT_TARGET} to {@code TARGET}.</p>
 *
 * <p>Scenario B (negative control, same run): {@code UndeclaredTargetMixin} is a valid
 * {@code @Mixin} that no config ever lists. It must not move the index at all — that is the
 * semantic boundary "an unlisted mixin class is not a mixin" recorded as a real reading.</p>
 */
public class Round2Probe implements ModInitializer {

	private static final String LATE_TARGET = "com/example/probe/LateTarget";
	private static final String UNDECLARED_TARGET = "com/example/probe/UndeclaredTarget";

	@Override
	public void onInitialize() {
		Thread t = new Thread(this::run, "remold-round2-probe");
		t.setDaemon(true);
		t.start();
	}

	private void run() {
		sleep(15000);
		p("=== BEFORE: window with no runtime config change yet ===");
		printIndex("before");
		printLookup("before", LATE_TARGET);
		printLookup("before", UNDECLARED_TARGET);
		printConfig("before");

		long stampBefore = stamp();
		long sizeBefore = size();

		p("=== SCENARIO A: rewriting mixins.json at runtime ===");
		rewriteConfig();
		sleep(1500);
		p("config file now: " + readConfig().replace("\n", " ").replaceAll("\\s+", " "));

		long deadline = System.nanoTime() + 40_000_000_000L;
		boolean moved = false;
		while (System.nanoTime() < deadline) {
			if (stamp() != stampBefore) {
				moved = true;
				break;
			}
			sleep(500);
		}
		sleep(2000);
		p("=== AFTER: stamp change observed=" + moved
			+ " stampBefore=" + stampBefore + " stampAfter=" + stamp()
			+ " sizeBefore=" + sizeBefore + " sizeAfter=" + size());
		printIndex("after");
		printLookup("after", LATE_TARGET);
		printConfig("after");

		p("=== SCENARIO B (negative control): undeclared mixin must not be indexed ===");
		printLookup("control", UNDECLARED_TARGET);
		long stampBeforeControl = stamp();
		sleep(3000);
		p("[CONTROL] stamp stable=" + (stamp() == stampBeforeControl)
			+ " stamp=" + stamp() + " undeclaredLookup=" + lookup(UNDECLARED_TARGET));
		p("=== PROBE DONE ===");
		Runtime.getRuntime().halt(0);
	}

	/** Lists LateTargetMixin in mixins + client + server lists of the runtime config copy. */
	private static void rewriteConfig() {
		try {
			Path config = configPath();
			String json = Files.readString(config, StandardCharsets.UTF_8);
			json = json.replace("\"mixins\": [", "\"mixins\": [\n    \"LateTargetMixin\",");
			if (json.contains("\"client\"")) {
				json = json.replace("\"client\": [", "\"client\": [\n    \"LateTargetMixin\",");
			} else {
				json = json.replace("\"injectors\"", "\"client\": [\n    \"LateTargetMixin\"\n  ],\n  \"server\": [\n    \"LateTargetMixin\"\n  ],\n  \"injectors\"");
			}
			if (json.contains("\"server\"")) {
				json = json.replace("\"server\": [", "\"server\": [\n    \"LateTargetMixin\",");
			}
			Files.writeString(config, json, StandardCharsets.UTF_8);
			p("[CONFIG] wrote runtime config " + config);
		} catch (Throwable t) {
			p("[CONFIG] rewrite failed: " + t);
		}
	}

	private static Path configPath() {
		Path src = Path.of("src/main/resources/example_mod.mixins.json");
		Path build = Path.of("build/resources/main/example_mod.mixins.json");
		return Files.exists(build) ? build : src;
	}

	private static String readConfig() {
		try {
			return Files.readString(configPath(), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			return "<unreadable: " + t + ">";
		}
	}

	private static void printIndex(String tag) {
		p("[INDEX " + tag + "] available=" + available() + " size=" + size() + " stamp=" + stamp());
	}

	private static long stamp() {
		try {
			return ((Number) call("remold.reflect.mixin.MixinTargetIndex", "stamp")).longValue();
		} catch (Throwable t) {
			return -1L;
		}
	}

	private static long size() {
		try {
			return ((Number) call("remold.reflect.mixin.MixinTargetIndex", "size")).longValue();
		} catch (Throwable t) {
			return -1L;
		}
	}

	private static boolean available() {
		try {
			return Boolean.TRUE.equals(call("remold.reflect.mixin.MixinTargetIndex", "available"));
		} catch (Throwable t) {
			return false;
		}
	}

	private static void printLookup(String tag, String internalName) {
		p("[LOOKUP " + tag + "] " + internalName + " -> " + lookup(internalName));
	}

	private static Object lookup(String internalName) {
		try {
			Class<?> idx = Class.forName("remold.reflect.mixin.MixinTargetIndex", true,
			 ClassLoader.getSystemClassLoader());
			return idx.getMethod("lookup", String.class).invoke(null, internalName);
		} catch (Throwable t) {
			return "unavailable: " + t;
		}
	}

	private static Object call(String className, String method) throws Exception {
		Class<?> type = Class.forName(className, true, ClassLoader.getSystemClassLoader());
		return type.getMethod(method).invoke(null);
	}

	private static void printConfig(String tag) {
		try {
			Class<?> envType = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true,
			 ClassLoader.getSystemClassLoader());
			// 与第一轮探针同口径：只有 activeTransformer 上的 processor 才是真正组装好的那个。
			Object env = envType.getMethod("getCurrentEnvironment").invoke(null);
			Object transformer = envType.getMethod("getActiveTransformer").invoke(env);
			Object processor = transformer == null ? null : fieldOf(transformer, "processor");
			Object configs = processor == null ? null : fieldOf(processor, "configs");
			if (!(configs instanceof List<?> list)) {
				p("[CONFIG " + tag + "] configs=" + configs);
				return;
			}
			List<String> mappingSample = new ArrayList<>();
			for (Object config : list) {
				Object mapping = fieldOf(config, "mapping");
				Object mixinMapping = fieldOf(config, "mixinMapping");
				Object unhandled = fieldOf(config, "unhandledTargets");
				String name = String.valueOf(invokeOr(config, "getName"));
				int size = mixinMapping instanceof Map<?, ?> m ? m.size() : -1;
				boolean hasLate = false;
				if (mixinMapping instanceof Map<?, ?> m) {
					for (Object key : m.keySet()) {
						String k = String.valueOf(key).replace('.', '/');
						if (k.contains("LateTarget")) hasLate = true;
					}
				}
				p("[CONFIG " + tag + "] " + name + " mixinMapping.size=" + size
					+ " hasLateTarget=" + hasLate + " unhandledTargets=" + unhandled);
				if (mixinMapping instanceof Map<?, ?> m && mappingSample.size() < 3) {
					for (Object key : m.keySet()) {
						mappingSample.add(String.valueOf(key));
						if (mappingSample.size() >= 3) break;
					}
				}
			}
			p("[CONFIG " + tag + "] mappingKeySample=" + mappingSample);
		} catch (Throwable t) {
			p("[CONFIG " + tag + "] failed: " + t);
		}
	}

	private static Object fieldOf(Object target, String name) {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field f = type.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			} catch (NoSuchFieldException ignored) {
				type = type.getSuperclass();
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}

	private static Object invokeOr(Object target, String method) {
		try {
			Method m = target.getClass().getMethod(method);
			return m.invoke(target);
		} catch (Throwable t) {
			return "?";
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void p(String line) {
		System.out.println("[LF-PROBE] " + line);
	}
}

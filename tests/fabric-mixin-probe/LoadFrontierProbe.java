package com.example.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.fabricmc.api.ModInitializer;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Load-frontier / mixin target index probe (real Fabric Knot runtime).
 *
 * <p>Answers two questions with raw readings, never with inference:</p>
 * <ol>
 *   <li>What exactly do {@code mixinMapping}, {@code unhandledTargets} and
 *       {@code config.mixins -> info.getTargetClasses()} contain, in which key form
 *       (dotted vs slash), and what are the differences between the three?</li>
 *   <li>Is {@code MixinTargetIndex} still {@code UNKNOWN} at mod-init time, and does the
 *       production ready-gate ({@code MixinHotswapPlugin.isHostReady()}) flip it?</li>
 * </ol>
 *
 * <p>Everything Remold-side is reached by reflection: the probe compiles without Remold on
 * the classpath (the agent jar is appended at premain).</p>
 */
public class LoadFrontierProbe implements ModInitializer {

	private static final String CLASS_TARGET = "com/example/item/RemoldWandItem";
	private static final String STRING_TARGET = "com/example/probe/StringTarget";

	@Override
	public void onInitialize() {
		p("=== WINDOW 1: mod init (no hot-swap batch has run yet) ===");
		printIndexState("window1");
		printConfigState("window1");

		Thread t = new Thread(() -> {
			sleep(12000);
			p("=== WINDOW 2: after host ready ===");
			printIndexState("window2-before-ready-gate");
			boolean ready = callReadyGate();
			p("readyGate.isHostReady()=" + ready);
			printIndexState("window2-after-ready-gate");
			printThreeWayTable();
			printLookups();
			p("=== PROBE DONE ===");
			Runtime.getRuntime().halt(0);
		}, "remold-lf-probe");
		t.setDaemon(true);
		t.start();
	}

	/** Invokes the production ready gate reflectively (same code the batch loop calls). */
	private static boolean callReadyGate() {
		try {
			Class<?> plugin = Class.forName("remold.mixin.MixinHotswapPlugin", true,
			 ClassLoader.getSystemClassLoader());
			Object instance = plugin.getDeclaredConstructor().newInstance();
			Method m = plugin.getMethod("isHostReady");
			return Boolean.TRUE.equals(m.invoke(instance));
		} catch (Throwable t) {
			p("readyGate.call failed: " + t);
			return false;
		}
	}

	private static void printIndexState(String tag) {
		try {
			Class<?> idx = Class.forName("remold.reflect.mixin.MixinTargetIndex", true,
			 ClassLoader.getSystemClassLoader());
			Object available = idx.getMethod("available").invoke(null);
			Object size = idx.getMethod("size").invoke(null);
			Object stamp = idx.getMethod("stamp").invoke(null);
			p("[INDEX " + tag + "] available=" + available + " size=" + size + " stamp=" + stamp);
		} catch (Throwable t) {
			p("[INDEX " + tag + "] unavailable: " + t);
		}
	}

	private static void printLookups() {
		try {
			Class<?> idx = Class.forName("remold.reflect.mixin.MixinTargetIndex", true,
			 ClassLoader.getSystemClassLoader());
			Method lookup = idx.getMethod("lookup", String.class);
			p("[LOOKUP] " + CLASS_TARGET + " -> " + lookup.invoke(null, CLASS_TARGET));
			p("[LOOKUP] " + STRING_TARGET + " -> " + lookup.invoke(null, STRING_TARGET));
			p("[LOOKUP] " + "com/example/ExampleMod" + " -> " + lookup.invoke(null, "com/example/ExampleMod"));
		} catch (Throwable t) {
			p("[LOOKUP] unavailable: " + t);
		}
	}

	private static void printConfigState(String tag) {
		Object processor = processor();
		if (processor == null) {
			p("[CONFIG " + tag + "] processor=null");
			return;
		}
		Object configs = field(processor, "configs");
		p("[CONFIG " + tag + "] processor=" + processor.getClass().getName()
			+ " configsType=" + (configs == null ? "null" : configs.getClass().getName())
			+ " configsSize=" + (configs instanceof List<?> l ? l.size() : -1));
	}

	/** Raw three-way dump: mixinMapping keys, unhandledTargets, per-Mixin getTargetClasses. */
	private static void printThreeWayTable() {
		Object processor = processor();
		if (processor == null) {
			p("[TABLE] processor=null (Mixin not selected yet)");
			return;
		}
		Object configsObject = field(processor, "configs");
		if (!(configsObject instanceof List<?> configs)) {
			p("[TABLE] configs not a list: " + configsObject);
			return;
		}
		Set<String> unionMapping = new TreeSet<>();
		Set<String> unionUnhandled = new TreeSet<>();
		Set<String> unionTargets = new TreeSet<>();
		Set<String> rawSample = new TreeSet<>();

		for (Object config : configs) {
			String name = String.valueOf(invoke(config, "getName"));
			p("[CONFIG] " + name);
			Object mapping = field(config, "mixinMapping");
			if (mapping instanceof Map<?, ?> map) {
				for (Object key : map.keySet()) {
					String raw = String.valueOf(key);
					rawSample.add("mapping:" + raw);
					unionMapping.add(normalize(raw));
				}
				p("  mixinMapping.size()=" + map.size() + " keys(sample)=" + sample(map.keySet()));
			}
			Object unhandled = field(config, "unhandledTargets");
			if (unhandled instanceof Iterable<?> it) {
				List<String> raws = new ArrayList<>();
				for (Object o : it) {
					String raw = String.valueOf(o);
					rawSample.add("unhandled:" + raw);
					raws.add(raw);
					unionUnhandled.add(normalize(raw));
				}
				p("  unhandledTargets=" + raws);
			}
			Object mixins = field(config, "mixins");
			if (mixins instanceof Iterable<?> it) {
				for (Object info : it) {
					Object targets = invoke(info, "getTargetClasses");
					String mixinName = String.valueOf(invoke(info, "getClassName"));
					p("  mixin " + mixinName + " getTargetClasses=" + targets);
					if (targets instanceof Iterable<?> tit) {
						for (Object t : tit) {
							String raw = String.valueOf(t);
							rawSample.add("targets:" + raw);
							unionTargets.add(normalize(raw));
						}
					}
				}
			}
		}

		p("[TABLE] rawSamples=" + rawSample);
		p("[TABLE] union.mapping=" + unionMapping);
		p("[TABLE] union.unhandled=" + unionUnhandled);
		p("[TABLE] union.targets=" + unionTargets);
		Set<String> all = new LinkedHashSet<>();
		all.addAll(unionMapping);
		all.addAll(unionUnhandled);
		all.addAll(unionTargets);
		p("[TABLE] union.all=" + all);
		p("[TABLE] inTargetsOnly=" + diff(unionTargets, unionMapping, unionUnhandled));
		p("[TABLE] inMappingOnly=" + diff(unionMapping, unionTargets, unionUnhandled));
		p("[TABLE] inUnhandledOnly=" + diff(unionUnhandled, unionMapping, unionTargets));
		p("[TABLE] classTargetPresent=" + all.contains(CLASS_TARGET)
			+ " stringTargetPresent=" + all.contains(STRING_TARGET));
	}

	private static Set<String> diff(Set<String> left, Set<String> a, Set<String> b) {
		Set<String> out = new TreeSet<>(left);
		out.removeAll(a);
		out.removeAll(b);
		return out;
	}

	private static String sample(Set<?> keys) {
		List<String> out = new ArrayList<>();
		int i = 0;
		for (Object k : keys) {
			out.add(String.valueOf(k));
			if (++i >= 6) break;
		}
		return out.toString();
	}

	private static String normalize(String name) {
		if (name == null) return "";
		String s = name;
		int at = s.indexOf(' ');           // e.g. "class net.example.Foo"
		if (at > 0) s = s.substring(at + 1);
		return s.trim().replace('.', '/');
	}

	private static Object processor() {
		try {
			Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
			if (transformer == null) return null;
			return field(transformer, "processor");
		} catch (Throwable t) {
			p("[PROCESSOR] failed: " + t);
			return null;
		}
	}

	private static Object field(Object target, String name) {
		if (target == null) return null;
		try {
			for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
				try {
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					return f.get(target);
				} catch (NoSuchFieldException ignored) {
					// keep walking
				}
			}
		} catch (Throwable t) {
			p("[FIELD] " + name + " failed: " + t);
		}
		return null;
	}

	private static Object invoke(Object target, String name) {
		if (target == null) return null;
		try {
			Method m = target.getClass().getMethod(name);
			m.setAccessible(true);
			return m.invoke(target);
		} catch (Throwable t) {
			return "ERR:" + t.getClass().getSimpleName();
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	private static void p(String line) {
		System.out.println("[LF-PROBE] " + line);
		System.out.flush();
	}
}

package com.example.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.ExampleMod;
import net.minecraft.client.MinecraftClient;

/**
 * 把 Remold 的批次回调接到 {@link HotSwapToast} 上。
 *
 * <p><b>为什么全反射</b>：模板 {@code compileOnly} 依赖的 api 已包含
 * {@code HotswapBatchReport} / {@code afterInternalBatchReport}，但这两个符号由运行期 Agent 提供；
 * 目标环境可能挂着更旧的 Agent（没有这些类型 / 方法），直接静态引用会让模板在旧 Agent 上抛
 * {@link NoClassDefFoundError} / {@link NoSuchMethodError}。因此这里用 {@link Proxy} 实现运行期的
 * {@code remold.HotswapBatchListener}，只按运行期接口的实际形态分流：</p>
 * <ul>
 *   <li>运行时接口<b>有</b> {@code afterInternalBatchReport} → 使用结构化报告（耗时 / Mixin / InitFix）；</li>
 *   <li>运行时接口<b>没有</b>（旧 Agent）→ 退化到 {@code afterInternalBatch}，只展示类名，
 *       并明确写“耗时 / Mixin / InitFix 数据不可用”。</li>
 * </ul>
 *
 * <p>回调线程是 Agent 的热更线程，不是客户端渲染线程；所有 {@code MinecraftClient} 交互都切到
 * 客户端线程执行。专用服务器没有客户端入口点，本类不会被加载。</p>
 */
public final class HotSwapToastBridge {

	private static boolean installed;

	private HotSwapToastBridge() {
	}

	/** 注册监听器；Remold 未挂载或接口缺失时只记录一条日志，不影响模组正常运行。 */
	public static synchronized void install() {
		if (installed) {
			return;
		}
		try {
			// Agent 的类由 system loader 加载；Fabric 的 Knot loader 不一定把 remold.*
			// 委托给 system，因此显式优先用 system loader 解析，找不到再回退默认加载器。
			Class<?> listenerType = loadAgentClass("remold.HotswapBatchListener");
			Class<?> agentType = loadAgentClass("remold.RemoldAgent");
			boolean reportSupported = hasMethod(listenerType, "afterInternalBatchReport");

			InvocationHandler handler = (proxy, method, args) -> {
				switch (method.getName()) {
					case "afterInternalBatchReport":
						onReport(args == null || args.length == 0 ? null : args[0]);
						return null;
					case "afterInternalBatch":
						// 新版 Agent 会同时发旧回调与报告；只在旧 Agent 上走这条降级路径，
						// 否则同一批次会弹两次。
						if (!reportSupported) {
							onLegacyBatch(args == null || args.length == 0 ? null : args[0]);
						}
						return null;
					case "onExternalClassRedefined":
						return null;
					case "toString":
						return "example-mod-hotswap-toast";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == (args == null || args.length == 0 ? null : args[0]);
					default:
						return null;
				}
			};

			Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
				new Class<?>[]{listenerType}, handler);
			agentType.getMethod("addBatchListener", listenerType).invoke(null, listener);
			installed = true;
			ExampleMod.LOGGER.info("[HotSwapToast] Remold batch listener registered (structured report: {})",
				reportSupported);
		} catch (ClassNotFoundException e) {
			// 没挂 Remold（普通玩家 / 直接 runClient）是正常路径。
			ExampleMod.LOGGER.info("[HotSwapToast] Remold agent not present; toast disabled");
		} catch (Throwable t) {
			ExampleMod.LOGGER.warn("[HotSwapToast] Failed to register Remold listener; toast disabled: {}", t.toString());
		}
	}

	private static void onReport(Object report) {
		if (report == null) {
			return;
		}
		try {
			long elapsed = longValue(call(report, "getElapsedMillis"), -1L);
			List<String> classes = stringList(call(report, "getRedefinedClasses"));
			List<HotSwapToastModel.MixinSummary> mixins = mixinSummaries(call(report, "getMixinChanges"));
			HotSwapToastModel.InitFixSummary initFix = initFixSummary(call(report, "getInitFixStats"));
			show(HotSwapToastModel.from(elapsed, classes, mixins, initFix));
		} catch (Throwable t) {
			ExampleMod.LOGGER.warn("[HotSwapToast] Failed to read batch report: {}", t.toString());
		}
	}

	private static void onLegacyBatch(Object definitions) {
		try {
			List<String> names = new ArrayList<>();
			if (definitions instanceof List<?> list) {
				for (Object definition : list) {
					Object clazz = call(definition, "getDefinitionClass");
					Object name = clazz instanceof Class<?> c ? c.getName() : null;
					names.add(name instanceof String s ? s : "?");
				}
			}
			show(HotSwapToastModel.legacy(names));
		} catch (Throwable t) {
			ExampleMod.LOGGER.warn("[HotSwapToast] Failed to read legacy batch: {}", t.toString());
		}
	}

	private static List<HotSwapToastModel.MixinSummary> mixinSummaries(Object changes) {
		if (!(changes instanceof List<?> list)) {
			return Collections.emptyList();
		}
		List<HotSwapToastModel.MixinSummary> out = new ArrayList<>(list.size());
		for (Object change : list) {
			if (change == null) {
				continue;
			}
			out.add(new HotSwapToastModel.MixinSummary(
				stringValue(call(change, "getMixinClass"), "?"),
				booleanValue(call(change, "isDeltaKnown"), false),
				intValue(call(change, "getUniqueFieldsAdded")),
				intValue(call(change, "getUniqueFieldsRemoved")),
				intValue(call(change, "getInjectHandlersAdded")),
				intValue(call(change, "getInjectHandlersRemoved")),
				intValue(call(change, "getUniqueFieldCount")),
				intValue(call(change, "getInjectHandlerCount"))));
		}
		return out;
	}

	private static HotSwapToastModel.InitFixSummary initFixSummary(Object stats) {
		if (stats == null) {
			return null;
		}
		return new HotSwapToastModel.InitFixSummary(
			stringList(call(stats, "getPatchedClasses")),
			intValue(call(stats, "getLiveInstances")),
			intValue(call(stats, "getPatchedFields")),
			intValue(call(stats, "getIncompleteFields")),
			stringList(call(stats, "getFieldNames")));
	}

	private static void show(HotSwapToastModel model) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		try {
			client.execute(() -> {
				try {
					if (client.getToastManager() == null) {
						return;
					}
					HotSwapToast existing = client.getToastManager()
						.getToast(HotSwapToast.class, HotSwapToast.TYPE);
					if (existing != null) {
						existing.refresh(model);
					} else {
						client.getToastManager().add(new HotSwapToast(model));
					}
				} catch (Throwable t) {
					ExampleMod.LOGGER.warn("[HotSwapToast] Failed to show toast: {}", t.toString());
				}
			});
		} catch (Throwable t) {
			// 客户端正在关闭 / 线程池已拒绝：热更本身已经成功，不要影响主链路。
			ExampleMod.LOGGER.warn("[HotSwapToast] Failed to schedule toast: {}", t.toString());
		}
	}

	/**
	 * 优先用 system loader 解析 Agent 侧类（agent jar 由 system loader 加载），
	 * 避免 Fabric Knot 的自定义 loader 看不到 {@code remold.*}。
	 */
	private static Class<?> loadAgentClass(String name) throws ClassNotFoundException {
		ClassLoader system = ClassLoader.getSystemClassLoader();
		if (system != null) {
			try {
				return Class.forName(name, false, system);
			} catch (ClassNotFoundException ignored) {
				// 回退到调用方 loader（例如非 Fabric 宿主把 agent 挂在同一 loader）。
			}
		}
		return Class.forName(name);
	}

	private static boolean hasMethod(Class<?> type, String name) {
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	private static Object call(Object target, String getter) {
		if (target == null) {
			return null;
		}
		try {
			return target.getClass().getMethod(getter).invoke(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>(list.size());
		for (Object item : list) {
			if (item != null) {
				out.add(String.valueOf(item));
			}
		}
		return out;
	}

	private static String stringValue(Object value, String fallback) {
		return value instanceof String s ? s : fallback;
	}

	private static boolean booleanValue(Object value, boolean fallback) {
		return value instanceof Boolean b ? b : fallback;
	}

	private static int intValue(Object value) {
		return value instanceof Number n ? n.intValue() : 0;
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number n ? n.longValue() : fallback;
	}
}

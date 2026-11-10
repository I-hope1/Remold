package remold.reflect.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import remold.RemoldAgent;

/**
 * 运行期 {@code *.mixins.json} 差异应用（纯反射，无 Mixin / 加载器编译期依赖）。
 *
 * <p>从配置删除一个 Mixin 名时，把它从 {@code MixinConfig} 的内部列表/映射/全局集合移除，
 * 再让宿主插件重织入受影响目标类；重新加入时复用 {@link NewMixinRegistrar} 登记。</p>
 *
 * <p>解析失败或内部结构不匹配时只 warn、不抛异常；资源监听线程不能被单个损坏文件打断。</p>
 */
public final class MixinConfigDiffer {

	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

	/** 被配置显式删除的 Mixin（点分类名 -> 当时解析的配置路径）。 */
	private static final Map<String, Path> REMOVED_FROM_CONFIG = new ConcurrentHashMap<>();

	/**
	 * 「配置里已列出、但字节码还没编译出来」的新 Mixin（点分类名 -> 待决记录）。
	 *
	 * <p><b>为什么需要</b>：IDE 里常见先保存 {@code mixins.json}（触发资源事件）再完成编译。
	 * 资源事件此刻拿不到 {@code .class}，但这不是错误，也不该要求用户重启或再存一次配置——
	 * 字节随后落到任一监控路径时，类批次会再次经过这里，待决集合据此自动接力登记。</p>
	 */
	private static final Map<String, Pending> PENDING_UNRESOLVED = new ConcurrentHashMap<>();

	/** 待决项的最长等待时间：超过后放弃并明确提示（不做无限重试）。 */
	static final long PENDING_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

	/** 待决项的最大重试次数（资源事件可能反复触发，必须有上限）。 */
	static final int PENDING_MAX_ATTEMPTS = 40;

	private MixinConfigDiffer() {
	}

	/** 一个待决的新 Mixin：它来自哪份配置、从什么时候开始等、重试过几次。 */
	private static final class Pending {
		private final Path config;
		private final long recordedNanos;
		private final AtomicInteger attempts = new AtomicInteger();

		private Pending(Path config) {
			this.config = config;
			this.recordedNanos = System.nanoTime();
		}

		private boolean expired() {
			return this.attempts.get() >= PENDING_MAX_ATTEMPTS
				|| System.nanoTime() - this.recordedNanos > PENDING_TTL_NANOS;
		}
	}

	/**
	 * 尚未拿到字节码的新列出 Mixin（诊断与测试用）。
	 *
	 * @return 点分类名集合的不可变快照
	 */
	public static Set<String> pendingUnresolvedMixins() {
		return Set.copyOf(PENDING_UNRESOLVED.keySet());
	}

	/** 清空待决集合（测试用；生产路径不应调用）。 */
	static void clearPendingUnresolvedMixins() {
		PENDING_UNRESOLVED.clear();
	}

	/** 该 Mixin 是否仍被配置显式删除；若配置已重新列出它，则自愈并返回 false。 */
	public static boolean isRemovedFromConfig(String dotClassName) {
		if (dotClassName == null) {
			return false;
		}
		Path configPath = REMOVED_FROM_CONFIG.get(dotClassName);
		if (configPath == null) {
			return false;
		}
		try {
			String json = Files.readString(configPath, StandardCharsets.UTF_8);
			Set<String> names = declaredMixinNames(json, "CLIENT");
			Set<String> serverNames = declaredMixinNames(json, "SERVER");
			if (serverNames != null) {
				if (names == null) {
					names = serverNames;
				} else {
					names.addAll(serverNames);
				}
			}
			int dot = dotClassName.lastIndexOf('.');
			String simpleName = dot < 0 ? dotClassName : dotClassName.substring(dot + 1);
			if (names != null && (names.contains(simpleName) || matchesRelativeName(dotClassName, names))) {
				REMOVED_FROM_CONFIG.remove(dotClassName);
				RemoldAgent.info("[Mixin] " + dotClassName + " is listed in " + configPath.getFileName()
					+ " again; allowing runtime registration");
				return false;
			}
		} catch (Throwable ignored) {
			// 读不到/解析失败时保持删除状态，宁可漏登记也不误重加。
		}
		return true;
	}

	/** 配置重新加入后再允许按“新增 Mixin”路径登记。 */
	public static void markReaddedToConfig(String dotClassName) {
		if (dotClassName != null) {
			REMOVED_FROM_CONFIG.remove(dotClassName);
		}
	}

	/**
	 * 配置里的名字是<b>相对名</b>，允许多层子包（{@code honkai.ProbeSubHotcMixin}），
	 * 因此自愈判定不能只比最后一段简单名：只要配置项是 {@code dotClassName} 的点分后缀就算已重新列出。
	 */
	private static boolean matchesRelativeName(String dotClassName, Set<String> names) {
		for (String name : names) {
			if (name == null || name.isEmpty()) {
				continue;
			}
			if (dotClassName.endsWith("." + name)) {
				return true;
			}
		}
		return false;
	}

	/** 资源回调入口。 */
	public static void onAssetsChanged(Set<Path> changedAssets, MixinConfigContext ctx) {
		if (changedAssets == null || changedAssets.isEmpty() || ctx == null || ctx.transformer() == null) {
			return;
		}
		try {
			Object processor = MixinReflection.readField(ctx.transformer(), "processor");
			if (processor == null) {
				return;
			}
			synchronized (processor) {
				Object configsObject = MixinReflection.readField(processor, "configs");
				if (!(configsObject instanceof List<?> configs)) {
					return;
				}
				Set<String> handled = new HashSet<>();
				for (Path asset : changedAssets) {
					if (asset == null || asset.getFileName() == null) {
						continue;
					}
					String fileName = asset.getFileName().toString();
					if (!fileName.endsWith(".mixins.json") || !handled.add(fileName)) {
						continue;
					}
					boolean matched = false;
					for (Object config : configs) {
						Object configName = MixinReflection.invoke(config, "getName");
						if (!fileName.equals(configName)) {
							continue;
						}
						matched = true;
						apply(config, asset, ctx);
					}
					if (!matched) {
						RemoldAgent.info("[Mixin] Ignoring mixin config " + asset
							+ ": no live MixinConfig with that file name is registered");
					}
				}
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to apply mixin config change: " + t);
		}
	}

	private static void apply(Object config, Path asset, MixinConfigContext ctx) {
		String json;
		try {
			json = Files.readString(asset, StandardCharsets.UTF_8);
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Unable to read mixin config " + asset + ": " + t);
			return;
		}
		Set<String> expected = declaredMixinNames(json, ctx.side());
		if (expected == null) {
			RemoldAgent.warn("[Mixin] Ignoring malformed/incomplete mixin config " + asset
				+ "; keeping the current config unchanged");
			return;
		}
		String mixinPackage = (String) MixinReflection.invoke(config, "getMixinPackage");
		if (mixinPackage == null) {
			return;
		}
		Map<String, Object> current = currentMixins(config, mixinPackage);

		List<String> removedNames = new ArrayList<>();
		List<String> addedNames = new ArrayList<>();
		List<String> affected = new ArrayList<>();
		for (Map.Entry<String, Object> entry : current.entrySet()) {
			if (!expected.contains(entry.getKey())) {
				removedNames.add(entry.getKey());
				collectTargets(entry.getValue(), affected);
				remove(config, entry.getKey(), entry.getValue());
				String removedDot = mixinPackage + entry.getKey();
				REMOVED_FROM_CONFIG.put(removedDot, asset.toAbsolutePath());
				PENDING_UNRESOLVED.remove(removedDot);
			}
		}
		for (String simpleName : expected) {
			if (current.containsKey(simpleName)) {
				continue;
			}
			String dotClassName = mixinPackage + simpleName;
			addedNames.add(simpleName);
			markReaddedToConfig(dotClassName);
			Object node = ctx.classNodeLoader() == null ? null : ctx.classNodeLoader().apply(dotClassName);
			if (node == null) {
				// 时序解耦：配置先到、字节后到是 IDE 的正常节奏，不是失败。
				// 记入待决集，字节落地后由 retryPending(...) 自动接力登记；不要求用户重启，
				// 也不要求用户再存一次配置。
				PENDING_UNRESOLVED.computeIfAbsent(dotClassName, key -> new Pending(asset.toAbsolutePath()));
				RemoldAgent.info("[Mixin] Bytecode for newly listed mixin " + dotClassName
					+ " is not compiled yet; it will be registered automatically once the .class lands"
					+ " in a watched output directory (no restart, no need to re-save the config)."
					+ " Channels already tried: " + MixinClassByteSources.CHANNELS
					+ " -> the loader's Mixin service resource stream");
				continue;
			}
			PENDING_UNRESOLVED.remove(dotClassName);
			registerAddedMixin(ctx, dotClassName, node, affected);
		}
		RemoldAgent.info("[Mixin] Config diff " + asset.getFileName() + ": removed=" + removedNames
			+ " added=" + addedNames + " affected=" + affected);
		if (!affected.isEmpty() && ctx.targetScheduler() != null) {
			ctx.targetScheduler().accept(affected);
		}
	}

	/**
	 * 接力登记待决的新 Mixin：字节码一旦可读就立即完成运行期登记与目标重织入。
	 *
	 * <p>由宿主插件在「有类变更进入热更流程」时调用（{@code plugin-mixin} 在 Mixin 类处理
	 * 收尾处触发）。不在资源监听线程里轮询：只在真实的类变更事件上顺带尝试一次，成本 O(待决数)。</p>
	 *
	 * <p><b>放弃语义</b>：超过 {@link #PENDING_MAX_ATTEMPTS} 次或 {@link #PENDING_TTL_NANOS} 仍未拿到
	 * 字节，或配置已不再列出该名字，就把该项从待决集移除并明确 warn——绝不无限重试、也绝不静默。</p>
	 *
	 * @param ctx 与资源差异相同的上下文（字节来源 / 抑制器 / 目标调度）
	 */
	public static void retryPending(MixinConfigContext ctx) {
		if (ctx == null || ctx.transformer() == null || PENDING_UNRESOLVED.isEmpty()) {
			return;
		}
		try {
			Object processor = MixinReflection.readField(ctx.transformer(), "processor");
			if (processor == null) {
				return;
			}
			synchronized (processor) {
				Object configsObject = MixinReflection.readField(processor, "configs");
				if (!(configsObject instanceof List<?> configs)) {
					return;
				}
				for (String dotClassName : new ArrayList<>(PENDING_UNRESOLVED.keySet())) {
					Pending pending = PENDING_UNRESOLVED.get(dotClassName);
					if (pending == null) {
						continue;
					}
					if (pending.expired()) {
						PENDING_UNRESOLVED.remove(dotClassName);
						RemoldAgent.warn("[Mixin] Gave up waiting for the bytecode of the newly listed mixin "
							+ dotClassName + " after " + pending.attempts.get() + " attempt(s);"
							+ " compile it into a watched output directory, or restart once.");
						continue;
					}
					Object config = NewMixinRegistrar.findOwningConfig(configs, dotClassName);
					if (config == null || !stillListedInConfig(pending.config, ctx, dotClassName)) {
						PENDING_UNRESOLVED.remove(dotClassName);
						continue;
					}
					Object node = ctx.classNodeLoader() == null ? null : ctx.classNodeLoader().apply(dotClassName);
					pending.attempts.incrementAndGet();
					if (node == null) {
						continue;
					}
					List<String> affected = new ArrayList<>();
					registerAddedMixin(ctx, dotClassName, node, affected);
					PENDING_UNRESOLVED.remove(dotClassName);
					RemoldAgent.info("[Mixin] Deferred mixin " + dotClassName
						+ " is now registered; affected target(s): " + affected);
					if (!affected.isEmpty() && ctx.targetScheduler() != null) {
						ctx.targetScheduler().accept(affected);
					}
				}
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to retry deferred mixin registrations: " + t);
		}
	}

	/** 登记一个新列出的 Mixin，把命中的目标类（点分名）累加进 {@code affected}。 */
	private static void registerAddedMixin(MixinConfigContext ctx, String dotClassName, Object node,
			List<String> affected) {
		try {
			for (String target : NewMixinRegistrar.tryRegister(
					ctx.transformer(), dotClassName, node,
					ctx.suppress(), ctx.restore(), ctx.loaderHook())) {
				affected.add(target.replace('/', '.'));
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to register newly listed mixin " + dotClassName + ": " + t);
		}
	}

	/** 配置里是否仍列出该 Mixin；读不到配置时保守地返回 {@code true}（交给重试上限兜底）。 */
	private static boolean stillListedInConfig(Path configPath, MixinConfigContext ctx, String dotClassName) {
		if (configPath == null) {
			return true;
		}
		try {
			String json = Files.readString(configPath, StandardCharsets.UTF_8);
			Set<String> names = declaredMixinNames(json, ctx.side());
			if (names == null) {
				return true;
			}
			return names.stream().anyMatch(name -> dotClassName.equals(name)
				|| dotClassName.endsWith("." + name) || dotClassName.endsWith("$" + name));
		} catch (Throwable ignored) {
			return true;
		}
	}

	/** 解析当前侧（common + client/server）声明的 Mixin 简单名。 */
	public static Set<String> declaredMixinNames(String json, String side) {
		if (json.indexOf('{') < 0 || json.indexOf('}') < 0) {
			return null;
		}
		List<String> common = quotedArray(json, "mixins");
		if (common == null) {
			return null;
		}
		Set<String> names = new LinkedHashSet<>(common);
		if (side != null && !side.isEmpty()) {
			String key = "CLIENT".equalsIgnoreCase(side) ? "client"
				: "SERVER".equalsIgnoreCase(side) ? "server" : null;
			if (key != null) {
				List<String> sided = quotedArray(json, key);
				if (sided == null) {
					return null;
				}
				names.addAll(sided);
			}
		}
		return names;
	}

	/** 返回数组内的字符串；key 不存在返回空列表，key 存在但数组不完整返回 {@code null}。 */
	public static List<String> quotedArray(String json, String key) {
		int keyIndex = json.indexOf("\"" + key + "\"");
		if (keyIndex < 0) {
			return Collections.emptyList();
		}
		int open = json.indexOf('[', keyIndex);
		if (open < 0) {
			return null;
		}
		int close = json.indexOf(']', open);
		if (close < 0) {
			return null;
		}
		List<String> out = new ArrayList<>();
		Matcher matcher = QUOTED.matcher(json.substring(open + 1, close));
		while (matcher.find()) {
			out.add(matcher.group(1));
		}
		return out;
	}

	private static Map<String, Object> currentMixins(Object config, String mixinPackage) {
		Map<String, Object> out = new HashMap<>();
		Object mixinsObject = MixinReflection.readField(config, "mixins");
		if (!(mixinsObject instanceof List<?> mixins)) {
			return out;
		}
		for (Object mixin : new ArrayList<>(mixins)) {
			Object className = MixinReflection.invoke(mixin, "getClassName");
			if (className instanceof String full && full.startsWith(mixinPackage)) {
				out.put(full.substring(mixinPackage.length()), mixin);
			}
		}
		return out;
	}

	private static void collectTargets(Object mixinInfo, List<String> out) {
		Object targets = MixinReflection.invoke(mixinInfo, "getTargetClasses");
		if (!(targets instanceof List<?> list)) {
			return;
		}
		for (Object target : list) {
			if (target != null) {
				out.add(String.valueOf(target).replace('/', '.'));
			}
		}
	}

	private static void remove(Object config, String simpleName, Object info) {
		Object mixinsObject = MixinReflection.readField(config, "mixins");
		if (mixinsObject instanceof List<?> mixins) {
			mixins.remove(info);
		}
		Object mappingObject = MixinReflection.readField(config, "mixinMapping");
		if (mappingObject instanceof Map<?, ?> mapping) {
			for (Object value : mapping.values()) {
				if (value instanceof List<?> list) {
					list.remove(info);
				}
			}
			Iterator<? extends Map.Entry<?, ?>> entries = mapping.entrySet().iterator();
			while (entries.hasNext()) {
				Map.Entry<?, ?> entry = entries.next();
				if (entry.getValue() instanceof List<?> list && list.isEmpty()) {
					entries.remove();
				}
			}
		}
		Object unhandled = MixinReflection.readField(config, "unhandledTargets");
		if (unhandled instanceof Set<?> set) {
			Object targets = MixinReflection.invoke(info, "getTargetClasses");
			if (targets instanceof List<?> list) {
				for (Object target : list) {
					set.remove(String.valueOf(target).replace('/', '.'));
				}
			}
		}
		Object mixinClasses = MixinReflection.readField(config, "mixinClasses");
		if (mixinClasses instanceof List<?> list) {
			list.remove(simpleName);
		}
		Object global = MixinReflection.readField(config, "globalMixinList");
		if (global instanceof Set<?> set) {
			Object mixinPackage = MixinReflection.invoke(config, "getMixinPackage");
			if (mixinPackage instanceof String pkg) {
				set.remove(pkg + simpleName);
			}
		}
	}
}

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
	private static final Map<String, Path> REMOVED_FROM_CONFIG = new java.util.concurrent.ConcurrentHashMap<>();

	private MixinConfigDiffer() {
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
			if (names != null && names.contains(simpleName)) {
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
				REMOVED_FROM_CONFIG.put(mixinPackage + entry.getKey(), asset.toAbsolutePath());
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
				RemoldAgent.warn("[Mixin] Cannot read bytecode for newly listed mixin " + dotClassName
					+ "; add the class (or compile) once, then the config diff can register it");
				continue;
			}
			try {
				for (String target : NewMixinRegistrar.tryRegister(
						ctx.transformer(), dotClassName, node, ctx.loadedTargetSuppressor())) {
					affected.add(target.replace('/', '.'));
				}
			} catch (Throwable t) {
				RemoldAgent.warn("[Mixin] Failed to register newly listed mixin " + dotClassName + ": " + t);
			}
		}
		RemoldAgent.info("[Mixin] Config diff " + asset.getFileName() + ": removed=" + removedNames
			+ " added=" + addedNames + " affected=" + affected);
		if (!affected.isEmpty() && ctx.targetScheduler() != null) {
			ctx.targetScheduler().accept(affected);
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

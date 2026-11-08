package remold.mixin;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * 运行期 Mixin 配置文件的差异应用：当用户从 {@code *.mixins.json} 里删除（或重新加入）一个
 * Mixin 名时，把变化同步到已经解析的 {@code MixinConfig}，并重织入受影响的目标类。
 *
 * <p>Mixin 的配置在启动时解析成 {@code MixinInfo} 列表，运行期改 JSON 不会自动重读。本类
 * 在 Remold 的资源文件回调（{@code onAssetsChanged}）里做三件事：</p>
 * <ol>
 *   <li>读 JSON，提取当前侧（common / client / server）声明的 Mixin 简单名；</li>
 *   <li>删除：从 {@code MixinConfig.mixins} / {@code mixinMapping} / 全局去重集合移除对应
 *       {@code MixinInfo}，再调度其目标类重织入（去掉旧注入）；</li>
 *   <li>新增：读取类字节交给 {@link NewMixinRegistrar}（与新增 Mixin 类共用同一条登记路径），
 *       再调度目标类重织入。</li>
 * </ol>
 *
 * <p>只处理文件名与已注册 {@code MixinConfig.getName()} 完全一致的资源；解析失败或内部结构
 * 不匹配时只 warn、不抛异常（资源监听线程不能被单个损坏文件打断）。</p>
 */
final class MixinConfigDiff {

	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

	/** 被配置文件显式删除、尚未重新加入的 Mixin（点分类名）。 */
	private static final Set<String> REMOVED_FROM_CONFIG = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private MixinConfigDiff() {
	}

	/** 该 Mixin 是否被配置显式删除且尚未重新加入。 */
	static boolean isRemovedFromConfig(String dotClassName) {
		return dotClassName != null && REMOVED_FROM_CONFIG.contains(dotClassName);
	}

	/** 配置重新加入后再允许按“新增 Mixin”路径登记。 */
	static void markReaddedToConfig(String dotClassName) {
		if (dotClassName != null) {
			REMOVED_FROM_CONFIG.remove(dotClassName);
		}
	}

	/** 资源回调入口。 */
	static void onAssetsChanged(Set<Path> changedAssets) {
		if (changedAssets == null || changedAssets.isEmpty()) {
			return;
		}
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return;
		}
		try {
			Object processor = readField(transformer, "processor");
			if (processor == null) {
				return;
			}
			synchronized (processor) {
				Object configsObject = readField(processor, "configs");
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
						Object configName = invoke(config, "getName");
						if (!fileName.equals(configName)) {
							continue;
						}
						matched = true;
						apply(transformer, config, asset);
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

	private static void apply(IMixinTransformer transformer, Object config, Path asset) {
		String json;
		try {
			json = Files.readString(asset, StandardCharsets.UTF_8);
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Unable to read mixin config " + asset + ": " + t);
			return;
		}
		Set<String> expected = declaredMixinNames(json);
		if (expected == null) {
			RemoldAgent.warn("[Mixin] Ignoring malformed/incomplete mixin config " + asset
				+ "; keeping the current config unchanged");
			return;
		}
		String mixinPackage = (String) invoke(config, "getMixinPackage");
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
				REMOVED_FROM_CONFIG.add(mixinPackage + entry.getKey());
			}
		}
		for (String simpleName : expected) {
			if (current.containsKey(simpleName)) {
				continue;
			}
			String dotClassName = mixinPackage + simpleName;
			addedNames.add(simpleName);
			markReaddedToConfig(dotClassName);
			ClassNode node = loadClassNode(dotClassName);
			if (node == null) {
				RemoldAgent.warn("[Mixin] Cannot read bytecode for newly listed mixin " + dotClassName
					+ "; add the class (or compile) once, then the config diff can register it");
				continue;
			}
			try {
				for (String target : NewMixinRegistrar.tryRegister(transformer, dotClassName, node)) {
					affected.add(target.replace('/', '.'));
				}
			} catch (Throwable t) {
				RemoldAgent.warn("[Mixin] Failed to register newly listed mixin " + dotClassName + ": " + t);
			}
		}
		RemoldAgent.info("[Mixin] Config diff " + asset.getFileName() + ": removed=" + removedNames
			+ " added=" + addedNames + " affected=" + affected);
		if (!affected.isEmpty()) {
			MixinHotSwap.scheduleTargets(affected);
		}
	}

	private static Set<String> declaredMixinNames(String json) {
		if (json.indexOf('{') < 0 || json.indexOf('}') < 0) {
			return null;
		}
		List<String> common = quotedArray(json, "mixins");
		if (common == null) {
			return null;
		}
		Set<String> names = new LinkedHashSet<>(common);
		MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
		if (environment != null) {
			List<String> sided = null;
			switch (environment.getSide()) {
				case CLIENT:
					sided = quotedArray(json, "client");
					break;
				case SERVER:
					sided = quotedArray(json, "server");
					break;
				default:
					break;
			}
			if (sided == null) {
				return null;
			}
			names.addAll(sided);
		}
		return names;
	}

	/** 返回数组内的字符串；key 不存在返回空列表，key 存在但数组不完整返回 {@code null}。 */
	private static List<String> quotedArray(String json, String key) {
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
		Object mixinsObject = readField(config, "mixins");
		if (!(mixinsObject instanceof List<?> mixins)) {
			return out;
		}
		for (Object mixin : new ArrayList<>(mixins)) {
			Object className = invoke(mixin, "getClassName");
			if (className instanceof String full && full.startsWith(mixinPackage)) {
				out.put(full.substring(mixinPackage.length()), mixin);
			}
		}
		return out;
	}

	private static void collectTargets(Object mixinInfo, List<String> out) {
		Object targets = invoke(mixinInfo, "getTargetClasses");
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
		Object mixinsObject = readField(config, "mixins");
		if (mixinsObject instanceof List<?> mixins) {
			mixins.remove(info);
		}
		Object mappingObject = readField(config, "mixinMapping");
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
		Object unhandled = readField(config, "unhandledTargets");
		if (unhandled instanceof Set<?> set) {
			Object targets = invoke(info, "getTargetClasses");
			if (targets instanceof List<?> list) {
				for (Object target : list) {
					set.remove(String.valueOf(target).replace('/', '.'));
				}
			}
		}
		Object mixinClasses = readField(config, "mixinClasses");
		if (mixinClasses instanceof List<?> list) {
			list.remove(simpleName);
		}
		Object global = readField(config, "globalMixinList");
		if (global instanceof Set<?> set) {
			Object mixinPackage = invoke(config, "getMixinPackage");
			if (mixinPackage instanceof String pkg) {
				set.remove(pkg + simpleName);
			}
		}
	}

	private static ClassNode loadClassNode(String dotClassName) {
		try {
			InputStream in = MixinService.getService().getResourceAsStream(
				dotClassName.replace('.', '/') + ".class");
			if (in == null) {
				return null;
			}
			try (InputStream resource = in) {
				ClassNode node = new ClassNode();
				new ClassReader(resource).accept(node, 0);
				return node;
			}
		} catch (Throwable t) {
			return null;
		}
	}

	private static Object readField(Object target, String name) {
		if (target == null) {
			return null;
		}
		try {
			Field field = findField(target.getClass(), name);
			if (field == null) {
				return null;
			}
			field.setAccessible(true);
			return field.get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object invoke(Object target, String name) {
		if (target == null) {
			return null;
		}
		try {
			Method method = findMethod(target.getClass(), name);
			if (method == null) {
				return null;
			}
			method.setAccessible(true);
			return method.invoke(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Field findField(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 0) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		return null;
	}
}

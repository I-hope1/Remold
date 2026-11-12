package remold.evict;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import remold.RemoldAgent;
import remold.util.LibTool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 反射与序列化框架实例级缓存驱逐管理器（S4）。
 *
 * <p>用于在重定义完成后清除各第三方框架（Jackson、Gson 等）内部针对被重定义类的元数据缓存，
 * 使新增字段与类型变化对序列化器立即可见。</p>
 *
 * <p>本类只持有与具体游戏引擎无关的通用实现；引擎专属驱逐器（如 Arc {@code Json}）
 * 由对应扩展插件通过 {@link #registerEvictor} 自行注册，核心不持有其类名与私有字段名。</p>
 */
public final class ReflectionCacheManager {

	private static final List<ReflectionCacheEvictor> evictors = new CopyOnWriteArrayList<>();

	// 注册的特定框架实例缓存（使用弱键集合，避免内存泄漏）
	private static final Set<Object> registeredObjectMappers = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Set<Object> registeredGsons = Collections.newSetFromMap(new WeakHashMap<>());

	static {
		registerEvictor(new JacksonEvictor());
		registerEvictor(new GsonEvictor());
	}

	private ReflectionCacheManager() {}

	public static void registerEvictor(ReflectionCacheEvictor evictor) {
		if (evictor != null && !evictors.contains(evictor)) {
			evictors.add(evictor);
		}
	}

	public static void unregisterEvictor(ReflectionCacheEvictor evictor) {
		if (evictor != null) {
			evictors.remove(evictor);
		}
	}

	public static void registerObjectMapper(Object mapper) {
		if (mapper != null) {
			synchronized (registeredObjectMappers) {
				registeredObjectMappers.add(mapper);
			}
		}
	}

	public static void registerGson(Object gson) {
		if (gson != null) {
			synchronized (registeredGsons) {
				registeredGsons.add(gson);
			}
		}
	}

	/**
	 * 对指定的重定义类集合执行所有已注册框架的缓存驱逐。
	 *
	 * @param redefinedClasses 重定义成功的类集合
	 * @return 总驱逐条目/实例数
	 */
	public static int evictAll(Set<Class<?>> redefinedClasses) {
		if (redefinedClasses == null || redefinedClasses.isEmpty()) {
			return 0;
		}
		int totalEvicted = 0;
		for (ReflectionCacheEvictor evictor : evictors) {
			try {
				int count = evictor.evict(redefinedClasses);
				totalEvicted += count;
				if (count > 0 && RemoldAgent.DEBUG) {
					RemoldAgent.log("[CacheEvict] " + evictor.getName() + " evicted " + count + " item(s).");
				}
			} catch (Throwable t) {
				RemoldAgent.warn("[CacheEvict] Error running evictor " + evictor.getName() + ": " + t.getMessage());
			}
		}
		return totalEvicted;
	}

	//region 参数注解 / 泛型签名漂移点名

	/**
	 * 比较两个版本的类字节码，点名"参数注解或泛型签名发生变化"的成员。
	 *
	 * <p>为什么单独判这一类变化：字段/方法的<b>增删</b>有各自的安全门，而参数注解与泛型签名
	 * 变化既不改变成员的 JVM 描述符、也不改变类名 —— 重定义会成功，但已经解析过的反射句柄
	 * 与框架缓存里派生出的元数据仍按<b>旧签名</b>解释，且不会抛任何 {@code LinkageError}。
	 * 因此这类变化必须至少被点出成员名，不能静默按旧行为走。</p>
	 *
	 * <p><b>比较口径</b>（只比较两版都存在、且 {@code name + descriptor} 相同的成员）：</p>
	 * <ul>
	 *   <li>{@code Signature} 属性：字段的泛型类型、方法/构造器的泛型参数、返回类型与 throws；</li>
	 *   <li>参数注解：{@code RuntimeVisibleParameterAnnotations} 与
	 *       {@code RuntimeInvisibleParameterAnnotations}（按参数槽位 + 注解描述符 + 元素值比较；
	 *       同槽位的注解顺序不参与比较，缺失属性与空数组视为等价，避免误报）。</li>
	 * </ul>
	 *
	 * <p><b>不覆盖</b>：方法级注解、{@code AnnotationDefault}、{@code MethodParameters} 名字、
	 * 注解在类内其它成员上的重排。这些属于各自的批次。</p>
	 *
	 * @param oldBytes 重定义前的有效字节码（{@code null} 表示没有基线，直接返回空）
	 * @param newBytes 本次要生效的字节码
	 * @return 每个漂移成员一条描述（成员名 + 漂移的元数据种类）；无漂移返回空列表
	 */
	public static List<String> detectMemberMetadataDrift(byte[] oldBytes, byte[] newBytes) {
		List<String> result = new ArrayList<>();
		Map<String, List<String>> drift = collectMemberMetadataDrift(oldBytes, newBytes);
		if (drift.isEmpty()) return result;
		for (Map.Entry<String, List<String>> e : drift.entrySet()) {
			result.add(e.getKey() + " [" + String.join(", ", e.getValue()) + "]");
		}
		return result;
	}

	/**
	 * 生成"参数注解 / 泛型签名漂移"的告警文案（供接线处逐行 {@code warn}）。
	 *
	 * <p>最后一行固定登记能力边界：重定义<b>不会</b>刷新已经解析过的反射句柄，
	 * 缓存驱逐也只覆盖已注册缓存。这句必须保留 —— 缺了它，读到日志的人会误以为
	 * "已驱逐 = 已解决"，而用户自持的旧 {@code Method} 句柄仍然不安全。</p>
	 *
	 * @return 0 行表示无漂移；否则每成员一行 + 一行边界登记
	 */
	public static List<String> describeMemberMetadataDrift(String className, byte[] oldBytes, byte[] newBytes) {
		Map<String, List<String>> drift = collectMemberMetadataDrift(oldBytes, newBytes);
		if (drift.isEmpty()) return Collections.emptyList();
		List<String> lines = new ArrayList<>(drift.size() + 1);
		for (Map.Entry<String, List<String>> e : drift.entrySet()) {
			lines.add("[SIG-DRIFT] " + (className == null ? "?" : className) + "." + e.getKey()
				+ ": " + String.join(" + ", e.getValue()) + " changed.");
		}
		lines.add("[SIG-DRIFT] " + (className == null ? "?" : className)
			+ ": reflection handles resolved BEFORE this redefine are UNSAFE —"
			+ " treat their readings as unreliable and do not keep using them."
			+ " Handles held by user code are not repaired.");
		return lines;
	}

	/** 成员显示名 -> 漂移种类（有序：声明顺序）。解析失败时返回空（不让诊断路径打断热更）。 */
	private static Map<String, List<String>> collectMemberMetadataDrift(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null || newBytes == null || oldBytes.length == 0 || newBytes.length == 0) {
			return Collections.emptyMap();
		}
		Map<String, List<String>> drift = new LinkedHashMap<>();
		try {
			ClassNode oldNode = parse(oldBytes);
			ClassNode newNode = parse(newBytes);

			Map<String, FieldNode> oldFields = new LinkedHashMap<>();
			if (oldNode.fields != null) {
				for (FieldNode f : oldNode.fields) oldFields.put(f.name + ":" + f.desc, f);
			}
			if (newNode.fields != null) {
				for (FieldNode f : newNode.fields) {
					FieldNode of = oldFields.get(f.name + ":" + f.desc);
					if (of == null) continue;
					if (!Objects.equals(of.signature, f.signature)) {
						addDrift(drift, fieldLabel(f), "generic signature");
					}
				}
			}

			Map<String, MethodNode> oldMethods = new LinkedHashMap<>();
			if (oldNode.methods != null) {
				for (MethodNode m : oldNode.methods) oldMethods.put(m.name + ":" + m.desc, m);
			}
			if (newNode.methods != null) {
				for (MethodNode m : newNode.methods) {
					MethodNode om = oldMethods.get(m.name + ":" + m.desc);
					if (om == null) continue;
					if (!Objects.equals(om.signature, m.signature)) {
						addDrift(drift, methodLabel(m), "generic signature");
					}
					if (!renderParameterAnnotations(om).equals(renderParameterAnnotations(m))) {
						addDrift(drift, methodLabel(m), "parameter annotations");
					}
				}
			}
		} catch (Throwable t) {
			// 诊断只有告警价值：解析失败不能把热更链路带崩，也不能伪装成"没有漂移"以外的结论。
			if (RemoldAgent.DEBUG) RemoldAgent.log("[SIG-DRIFT] Cannot compare member metadata: " + t);
			return Collections.emptyMap();
		}
		return drift;
	}

	private static void addDrift(Map<String, List<String>> drift, String member, String aspect) {
		List<String> aspects = drift.computeIfAbsent(member, k -> new ArrayList<>(2));
		if (!aspects.contains(aspect)) aspects.add(aspect);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node,
			ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static String fieldLabel(FieldNode f) {
		return f.name + ": " + Type.getType(f.desc).getClassName();
	}

	/** {@code bar(java.lang.String, int)}：点名必须可读，形参描述符对人没有信息量。 */
	private static String methodLabel(MethodNode m) {
		Type[] args = Type.getMethodType(m.desc).getArgumentTypes();
		StringBuilder sb = new StringBuilder(m.name).append('(');
		for (int i = 0; i < args.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(args[i].getClassName());
		}
		return sb.append(')').toString();
	}

	/** 参数注解的可比较渲染（槽位 + 注解描述符 + 元素值；同槽位顺序归一化）。 */
	private static String renderParameterAnnotations(MethodNode mn) {
		if (isEmptyAnnotationArray(mn.visibleParameterAnnotations)
			&& isEmptyAnnotationArray(mn.invisibleParameterAnnotations)) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		appendAnnotatedParameters(sb, 'V', mn.visibleParameterAnnotations);
		appendAnnotatedParameters(sb, 'I', mn.invisibleParameterAnnotations);
		return sb.toString();
	}

	private static boolean isEmptyAnnotationArray(List<AnnotationNode>[] anns) {
		if (anns == null) return true;
		for (List<AnnotationNode> l : anns) {
			if (l != null && !l.isEmpty()) return false;
		}
		return true;
	}

	private static void appendAnnotatedParameters(StringBuilder sb, char kind, List<AnnotationNode>[] anns) {
		if (anns == null) return;
		for (int i = 0; i < anns.length; i++) {
			List<AnnotationNode> list = anns[i];
			if (list == null || list.isEmpty()) continue;
			List<String> rendered = new ArrayList<>(list.size());
			for (AnnotationNode an : list) rendered.add(renderAnnotation(an));
			Collections.sort(rendered); // 注解声明顺序不参与语义
			sb.append(kind).append(i).append('=').append(rendered).append(';');
		}
	}

	private static String renderAnnotation(AnnotationNode an) {
		StringBuilder sb = new StringBuilder("@").append(an.desc);
		if (an.values != null && !an.values.isEmpty()) {
			List<String> pairs = new ArrayList<>(an.values.size() / 2);
			for (int i = 0; i + 1 < an.values.size(); i += 2) {
				pairs.add(an.values.get(i) + "=" + renderAnnotationValue(an.values.get(i + 1)));
			}
			Collections.sort(pairs);
			sb.append('(').append(String.join(",", pairs)).append(')');
		}
		return sb.toString();
	}

	private static String renderAnnotationValue(Object v) {
		if (v instanceof AnnotationNode) return renderAnnotation((AnnotationNode) v);
		if (v instanceof List<?>) {
			List<String> items = new ArrayList<>();
			for (Object o : (List<?>) v) items.add(renderAnnotationValue(o));
			return "[" + String.join(",", items) + "]"; // 数组元素顺序有意义，不排序
		}
		if (v instanceof byte[]) return Arrays.toString((byte[]) v);
		if (v instanceof boolean[]) return Arrays.toString((boolean[]) v);
		if (v instanceof short[]) return Arrays.toString((short[]) v);
		if (v instanceof char[]) return Arrays.toString((char[]) v);
		if (v instanceof int[]) return Arrays.toString((int[]) v);
		if (v instanceof long[]) return Arrays.toString((long[]) v);
		if (v instanceof float[]) return Arrays.toString((float[]) v);
		if (v instanceof double[]) return Arrays.toString((double[]) v);
		return String.valueOf(v);
	}
	//endregion

	private static Field findField(Class<?> c, String name) {
		for (Class<?> curr = c; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
			try {
				return curr.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {}
		}
		return null;
	}

	/**
	 * Jackson {@code ObjectMapper} 实例级缓存与 {@code TypeFactory} 全局缓存驱逐器。
	 */
	public static final class JacksonEvictor implements ReflectionCacheEvictor {
		@Override
		public String getName() { return "Jackson"; }

		@Override
		public int evict(Set<Class<?>> redefinedClasses) {
			int count = 0;
			// 1. 全局 TypeFactory 缓存清理
			try {
				Class<?> tfClass = Class.forName("com.fasterxml.jackson.databind.type.TypeFactory");
				Method mDefault = tfClass.getMethod("defaultInstance");
				Object tf = mDefault.invoke(null);
				if (tf != null) {
					Method mClear = tfClass.getMethod("clearCache");
					mClear.invoke(tf);
					count++;
				}
			} catch (Throwable ignored) {}

			// 2. 实例级 SerializerCache 与 DeserializerCache 清理
			Set<Object> instances = new HashSet<>();
			synchronized (registeredObjectMappers) {
				instances.addAll(registeredObjectMappers);
			}

			try {
				Class<?> omClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
				Object[] heapInstances = LibTool.getInstances(omClass);
				if (heapInstances != null) {
					Collections.addAll(instances, heapInstances);
				}
			} catch (Throwable ignored) {}

			for (Object om : instances) {
				if (om == null) continue;
				try {
					Class<?> omc = om.getClass();
					// _serializerProvider._serializerCache
					Field fProv = findField(omc, "_serializerProvider");
					if (fProv != null) {
						fProv.setAccessible(true);
						Object prov = fProv.get(om);
						if (prov != null) {
							Field fCache = findField(prov.getClass(), "_serializerCache");
							if (fCache != null) {
								fCache.setAccessible(true);
								Object sCache = fCache.get(prov);
								if (sCache != null) {
									Method mFlush = sCache.getClass().getMethod("flush");
									mFlush.invoke(sCache);
									count++;
								}
							}
						}
					}
					// _deserializationContext._cache
					Field fCtx = findField(omc, "_deserializationContext");
					if (fCtx != null) {
						fCtx.setAccessible(true);
						Object ctx = fCtx.get(om);
						if (ctx != null) {
							Field fCache = findField(ctx.getClass(), "_cache");
							if (fCache != null) {
								fCache.setAccessible(true);
								Object dCache = fCache.get(ctx);
								if (dCache != null) {
									Method mFlush = dCache.getClass().getMethod("flush");
									mFlush.invoke(dCache);
									count++;
								}
							}
						}
					}
				} catch (Throwable t) {
					if (RemoldAgent.DEBUG) RemoldAgent.log("[JacksonEvict] Failed on instance: " + t);
				}
			}
			return count;
		}
	}

	/**
	 * Gson {@code typeTokenCache} 缓存驱逐器。
	 */
	public static final class GsonEvictor implements ReflectionCacheEvictor {
		@Override
		public String getName() { return "Gson"; }

		@Override
		public int evict(Set<Class<?>> redefinedClasses) {
			int count = 0;
			Set<Object> instances = new HashSet<>();
			synchronized (registeredGsons) {
				instances.addAll(registeredGsons);
			}

			try {
				Class<?> gsonClass = Class.forName("com.google.gson.Gson");
				Object[] heapInstances = LibTool.getInstances(gsonClass);
				if (heapInstances != null) {
					Collections.addAll(instances, heapInstances);
				}
			} catch (Throwable ignored) {}

			for (Object gson : instances) {
				if (gson == null) continue;
				try {
					Field fCache = findField(gson.getClass(), "typeTokenCache");
					if (fCache != null) {
						fCache.setAccessible(true);
						Object cache = fCache.get(gson);
						if (cache instanceof Map<?, ?> m) {
							m.clear();
							count++;
						}
					}
				} catch (Throwable t) {
					if (RemoldAgent.DEBUG) RemoldAgent.log("[GsonEvict] Failed on instance: " + t);
				}
			}
			return count;
		}
	}
}

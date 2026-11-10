package remold.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import remold.RemoldAgent;

/**
 * 把「Remold 已经拿到的新类字节」接入 Mixin 自己的字节码查找链。
 *
 * <p><b>为什么需要</b>：Mixin 读类不走 {@code Class.forName}，而是走
 * {@code MixinService.getService().getBytecodeProvider()}。这条链在 Fabric / Forge 上都只认
 * 「类加载器可见的资源」，因此 Remold 用 {@code [INJECTED]} 在内存里 defineClass 出来的新类，
 * 对 Mixin 完全不可见：</p>
 * <ul>
 *   <li>{@code ClassInfo.forName(Outer$1)} 会抛 {@code ClassNotFoundException} 并把
 *       {@code null} 写进 {@code ClassInfo.cache}（上游 {@code ClassInfo.forName} 的负缓存），
 *       随后 {@code MixinInfo.State.readInnerClasses()} 直接
 *       {@code Cannot invoke "ClassInfo.isProbablyStatic()" because "innerClass" is null}；</li>
 *   <li>{@code InnerClassGenerator.InnerClassInfo.accept()} 也从同一条链读原始字节，
 *       读不到就无法把新内部类重映射进目标类。</li>
 * </ul>
 *
 * <p><b>做法</b>：用一个动态代理包住当前的 {@code IMixinService}，只改写
 * {@code getBytecodeProvider()}，其余调用原样转发；代理里的 provider 只在**本次热更显式登记过**
 * 的内部名上命中，未登记的名字一律回落到宿主原 provider（绝不遮蔽普通类，避免把「已织入产物」
 * 当源码二次织入）。同时提供 {@link #evictClassInfoCache(Collection)} 清掉上游的负缓存。</p>
 *
 * <p><b>失败语义</b>：安装失败只降级并输出一条指向性诊断，不抛异常；调用方据此继续走原有路径
 * （此时行为与修复前一致，不会更坏）。</p>
 */
final class MixinBytecodeOverlay {

	/** 内部名 → 本次热更可用的最新源码级字节。 */
	private static final Map<String, byte[]> HOT_BYTES = new ConcurrentHashMap<>();

	private static final Object INSTALL_LOCK = new Object();

	private static final AtomicBoolean INSTALLED = new AtomicBoolean();

	private static final AtomicBoolean REPORTED_UNAVAILABLE = new AtomicBoolean();

	/** 已安装的服务代理；{@link #applyToConfigs()} 用它替换各 MixinConfig 捕获的服务引用。 */
	private static volatile Object SERVICE_PROXY;

	private MixinBytecodeOverlay() {
	}

	// ---------------------------------------------------------------- 登记表

	/**
	 * 登记一个类的「本轮最新字节」。
	 *
	 * @param dotClassName 点分类名
	 * @param bytes        源码级字节码；{@code null} 时忽略
	 */
	static void register(String dotClassName, byte[] bytes) {
		if (dotClassName == null || bytes == null || bytes.length == 0) {
			return;
		}
		HOT_BYTES.put(MixinInnerClassSupport.internalName(dotClassName), bytes);
	}

	/**
	 * 取登记过的字节；未登记返回 {@code null}。
	 *
	 * <p>点分名与内部名都接受：上游 {@code ClassInfo.forName} 传内部名，
	 * {@code InnerClassGenerator} 的部分调用点传点分名，两处归一化口径必须一致，
	 * 否则会出现「登记了但查不到」的静默 misses。</p>
	 */
	static byte[] lookup(String className) {
		if (className == null) {
			return null;
		}
		return HOT_BYTES.get(MixinInnerClassSupport.internalName(className));
	}

	/** 当前登记的全部内部名（诊断与测试用）。 */
	static Set<String> registeredInternalNames() {
		return new LinkedHashSet<>(HOT_BYTES.keySet());
	}

	/** 释放登记项（reload 被拒绝时回滚，避免旧字节长期遮蔽）。 */
	static void clear(Collection<String> dotOrInternalNames) {
		if (dotOrInternalNames == null) {
			return;
		}
		for (String name : dotOrInternalNames) {
			if (name == null) {
				continue;
			}
			HOT_BYTES.remove(MixinInnerClassSupport.internalName(name));
		}
	}

	/** 当前已安装的覆盖层是否生效。 */
	static boolean isInstalled() {
		return INSTALLED.get();
	}

	// ---------------------------------------------------------------- 安装

	/**
	 * 安装字节码提供方覆盖层（幂等、可重复调用、永不抛异常）。
	 *
	 * @return {@code true} 表示覆盖层生效；{@code false} 表示 Mixin 不在场或安装失败（已降级）
	 */
	static boolean install() {
		if (INSTALLED.get()) {
			return true;
		}
		synchronized (INSTALL_LOCK) {
			if (INSTALLED.get()) {
				return true;
			}
			try {
				ClassLoader loader = MixinBytecodeOverlay.class.getClassLoader();
				Class<?> mixinServiceClass = loadOptional("org.spongepowered.asm.service.MixinService", loader);
				if (mixinServiceClass == null) {
					reportUnavailable("Mixin is not on this class loader");
					return false;
				}
				Object liveService = mixinServiceClass.getMethod("getService").invoke(null);
				if (liveService == null) {
					reportUnavailable("MixinService.getService() returned null");
					return false;
				}
				if (Proxy.isProxyClass(liveService.getClass())
					&& Proxy.getInvocationHandler(liveService) instanceof ServiceHandler) {
					INSTALLED.set(true);
					return true;
				}

				Class<?> serviceInterface = loadOptional("org.spongepowered.asm.service.IMixinService", loader);
				if (serviceInterface == null) {
					reportUnavailable("IMixinService is not visible to the plugin class loader");
					return false;
				}

				Object liveProvider;
				try {
					Method providerMethod = liveService.getClass().getMethod("getBytecodeProvider");
					providerMethod.setAccessible(true);
					liveProvider = providerMethod.invoke(liveService);
				} catch (Throwable t) {
					reportUnavailable("MixinService.getBytecodeProvider() is unavailable: " + t);
					return false;
				}
				if (liveProvider == null) {
					reportUnavailable("Mixin bytecode provider is null");
					return false;
				}

				Object providerProxy = Proxy.newProxyInstance(loader,
					interfacesOf(liveProvider.getClass(), null), new ProviderHandler(liveProvider));
				Object serviceProxy = Proxy.newProxyInstance(loader,
					interfacesOf(liveService.getClass(), serviceInterface),
					new ServiceHandler(liveService, providerProxy));

				Object instance = staticField(mixinServiceClass, "instance");
				if (instance == null) {
					reportUnavailable("MixinService singleton is not initialized");
					return false;
				}
				if (!setField(instance, new String[]{"service", "theService", "serviceInstance"}, serviceProxy)) {
					reportUnavailable("cannot replace the IMixinService field (unknown Mixin version)");
					return false;
				}
				SERVICE_PROXY = serviceProxy;
				INSTALLED.set(true);
				RemoldAgent.info("[Mixin] Bytecode provider overlay installed: new class bytes are visible to"
					+ " ClassInfo / InnerClassGenerator (service="
					+ liveService.getClass().getSimpleName() + ")");
				return true;
			} catch (Throwable t) {
				reportUnavailable(t.toString());
				return false;
			}
		}
	}

	/**
	 * 把各 {@code MixinConfig} 启动期捕获的 {@code IMixinService} 引用也换成服务代理。
	 *
	 * <p><b>为什么静态替换不够</b>：{@link #install()} 只换了 {@code MixinService.instance}，
	 * 而 {@code MixinInfo} 构造器读 Mixin 字节走的是 {@code this.service}（{@code MixinConfig}
	 * 创建时捕获的<b>原始</b>服务实例）。于是“只存在于监控目录、类路径上不存在”的新 Mixin
	 * 在 {@code prepare()} 阶段仍然抛 {@code ClassNotFoundException: The specified mixin ... was not
	 * found}（真机 B3 实测）。这里把该 final 字段改写成服务代理，让捕获引用也走覆盖层。</p>
	 *
	 * @return 至少改写了一处配置时返回 {@code true}
	 */
	static boolean applyToConfigs() {
		Object proxy = SERVICE_PROXY;
		if (proxy == null) {
			return false;
		}
		try {
			Object transformer = MixinReWeaver.activeTransformer();
			Object processor = readField(transformer, "processor");
			Object configsObject = readField(processor, "configs");
			if (!(configsObject instanceof List)) {
				return false;
			}
			List<?> configs = (List<?>) configsObject;
			boolean patched = false;
			for (Object config : configs) {
				Field field = findField(config.getClass(), "service");
				if (field == null) {
					continue;
				}
				if (readField(config, "service") == proxy) {
					continue;
				}
				try {
					// 上游 MixinConfig.service 是非 final 字段（0.8.5 / 0.8.7 同构），直接写即可。
					field.set(config, proxy);
					patched = true;
				} catch (Throwable ignored) {
					// 单个配置改不动不影响其它配置；调用方会看到 patched=false 并保留既有诊断。
				}
			}
			return patched;
		} catch (Throwable t) {
			return false;
		}
	}

	private static Object readField(Object target, String name) {
		if (target == null) {
			return null;
		}
		Field field = findField(target.getClass(), name);
		if (field == null) {
			return null;
		}
		try {
			return field.get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void reportUnavailable(String reason) {
		if (REPORTED_UNAVAILABLE.compareAndSet(false, true)) {
			RemoldAgent.warn("[Mixin] Bytecode overlay unavailable (" + reason + "); new inner-class bytes stay"
				+ " invisible to Mixin's bytecode provider for this run. A Mixin that introduces a new anonymous"
				+ " class will still fail to reload until the class is on the loader's classpath, or restarted.");
		}
	}

	// ---------------------------------------------------------------- ClassInfo 负缓存

	/**
	 * 清掉上游 {@code ClassInfo.cache} 中这些内部名的条目。
	 *
	 * <p>上游 {@code ClassInfo.forName} 在读取失败时会把 {@code null} 写进缓存且永不重试，
	 * 所以「先失败过、后来字节才到」的场景必须显式驱逐，否则后续 reload 依旧拿到 {@code null}。</p>
	 *
	 * @param internalNames 内部名集合
	 * @return 实际移除的条目数；无法访问缓存时返回 -1
	 */
	static int evictClassInfoCache(Collection<String> internalNames) {
		if (internalNames == null || internalNames.isEmpty()) {
			return 0;
		}
		try {
			ClassLoader loader = MixinBytecodeOverlay.class.getClassLoader();
			Class<?> classInfo = loadOptional("org.spongepowered.asm.mixin.transformer.ClassInfo", loader);
			if (classInfo == null) {
				return -1;
			}
			Object cache = staticField(classInfo, "cache");
			if (!(cache instanceof Map)) {
				return -1;
			}
			Map<?, ?> map = (Map<?, ?>) cache;
			synchronized (map) {
				return evictFromCache(map, internalNames);
			}
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * 纯函数：从缓存映射里移除这些内部名。
	 *
	 * <p>负缓存的值本身就是 {@code null}，所以"移除成功"必须按 {@code containsKey} 判定，
	 * 不能按 {@code remove} 的返回值判定。</p>
	 *
	 * @param cache         上游的 {@code ClassInfo.cache}
	 * @param internalNames 要驱逐的内部名（点分名也接受）
	 * @return 实际移除的条目数
	 */
	static int evictFromCache(Map<?, ?> cache, Collection<String> internalNames) {
		if (cache == null || internalNames == null || internalNames.isEmpty()) {
			return 0;
		}
		int removed = 0;
		for (String name : internalNames) {
			if (name == null) {
				continue;
			}
			String internal = MixinInnerClassSupport.internalName(name);
			if (cache.containsKey(internal)) {
				cache.remove(internal);
				removed++;
			}
		}
		return removed;
	}

	// ---------------------------------------------------------------- 代理

	/** 服务代理：只改写 {@code getBytecodeProvider()}，其余原样转发。 */
	private static final class ServiceHandler implements InvocationHandler {

		private final Object delegate;
		private final Object providerProxy;

		ServiceHandler(Object delegate, Object providerProxy) {
			this.delegate = delegate;
			this.providerProxy = providerProxy;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return method.invoke(this, args);
			}
			if ("getBytecodeProvider".equals(method.getName())
				&& (args == null || args.length == 0)) {
				return this.providerProxy;
			}
			return forward(this.delegate, method, args);
		}
	}

	/** 提供方代理：只在已登记的内部名上命中，其余回落宿主。 */
	private static final class ProviderHandler implements InvocationHandler {

		private final Object delegate;

		ProviderHandler(Object delegate) {
			this.delegate = delegate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return method.invoke(this, args);
			}
			String name = method.getName();
			if (args != null && args.length >= 1 && args[0] instanceof String) {
				String internal = MixinInnerClassSupport.internalName((String) args[0]);
				byte[] bytes = lookup(internal);
				if (bytes != null) {
					if ("getClassNode".equals(name)) {
						return readNode(bytes, readerFlags(args));
					}
					if ("getClassBytes".equals(name)) {
						return bytes;
					}
				}
			}
			return forward(this.delegate, method, args);
		}
	}

	private static ClassNode readNode(byte[] bytes, int flags) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, flags);
		return node;
	}

	/** 上游 {@code getClassNode(name, runTransformers, readerFlags)} 的第三参。 */
	private static int readerFlags(Object[] args) {
		if (args != null && args.length >= 3 && args[2] instanceof Integer) {
			return (Integer) args[2];
		}
		return 0;
	}

	private static Object forward(Object delegate, Method method, Object[] args) throws Throwable {
		try {
			Method target = delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
			target.setAccessible(true);
			return target.invoke(delegate, args);
		} catch (NoSuchMethodException e) {
			// 接口默认方法等情况：直接在原对象上按接口方法调用。
			method.setAccessible(true);
			return method.invoke(delegate, args);
		} catch (InvocationTargetException e) {
			throw e.getCause() != null ? e.getCause() : e;
		}
	}

	/** 目标类自身实现的全部接口（保留 {@code instanceof} 语义），外加一个必选接口。 */
	private static Class<?>[] interfacesOf(Class<?> type, Class<?> extra) {
		Set<Class<?>> out = new LinkedHashSet<>();
		if (extra != null) {
			out.add(extra);
		}
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			Class<?>[] declared = c.getInterfaces();
			if (declared != null) {
				for (Class<?> iface : declared) {
					if (iface != null) {
						out.add(iface);
					}
				}
			}
		}
		return new ArrayList<>(out).toArray(new Class<?>[0]);
	}

	// ---------------------------------------------------------------- 反射工具

	private static Class<?> loadOptional(String className, ClassLoader loader) {
		try {
			return Class.forName(className, false, loader);
		} catch (Throwable t) {
			return null;
		}
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static Object staticField(Class<?> type, String name) throws Exception {
		Field f = findField(type, name);
		return f == null ? null : f.get(null);
	}

	private static boolean setField(Object target, String[] candidateNames, Object value) {
		for (String name : candidateNames) {
			Field f = findField(target.getClass(), name);
			if (f != null) {
				try {
					f.set(target, value);
					return true;
				} catch (Throwable ignored) {
				}
			}
		}
		return false;
	}
}

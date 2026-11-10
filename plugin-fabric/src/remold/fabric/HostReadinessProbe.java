package remold.fabric;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minecraft 系宿主的"启动就绪"探测：主循环是否已经跑起来。
 *
 * <p><b>为什么需要它</b>：Minecraft 启动期（{@code KnotClassLoader} + Mixin 转换器高频并发
 * 加载类）落入一个热更批次会让 JVM 挂死。{@code HotswapPlugin.isHostReady()} 是引擎给出的
 * 唯一窗口，但它的默认实现是 {@code true}——加载器不实现它就等于没有启动期保护。本类把
 * "主循环已进入首个 tick"翻译成引擎能理解的 {@link Verdict}。</p>
 *
 * <p><b>为什么是反射而不是编译期依赖</b>：{@code plugin-fabric} 由 Agent 在宿主类加载器里
 * 加载，不能把 Minecraft / Fabric 拖成编译期依赖（不同 MC 版本、不同映射集都会让坐标漂移）。
 * 因此只按<b>类名 + 访问器名</b>探测，且所有访问器名都按"多候选"处理（Yarn 与 Mojang 官方
 * 映射对同一语义给不同名字，例如 tick 计数在 Yarn 是 {@code getTicks}、在官方映射是
 * {@code getTickCount}）。类名本身两侧基本一致（{@code net.minecraft.server.MinecraftServer}、
 * {@code net.minecraft.client.MinecraftClient}）。</p>
 *
 * <p><b>失败语义（关键）</b>：探测只允许在<b>拿到明确信号</b>时给出 {@link Verdict#NOT_READY}。
 * 取不到任何信号（类不在、实例还没建、访问器名对不上、反射失败）一律
 * {@link Verdict#UNKNOWN}，由调用方回退到"按已就绪处理"——宁可漏保护，也不能因为映射对不上
 * 就永久饿死批次（门闩默认没有超时放行，饿死等于热更彻底不工作）。</p>
 *
 * <p><b>就绪判据</b>：</p>
 * <ul>
 *   <li><b>服务端</b>：{@code MinecraftServer} 实例已创建，且 tick 计数
 *       （{@code getTicks} / {@code getTickCount}）{@code > 0} → 就绪；{@code == 0} → 未就绪
 *       （实例已建、首个 tick 还没跑）。tick 计数取不到时退到 {@code isRunning}。</li>
 *   <li><b>客户端</b>：{@code MinecraftClient} / {@code Minecraft} 实例已创建，且已经进入世界
 *       （{@code world} / {@code level} 非空）→ 就绪。客户端停在主菜单时<b>刻意不判未就绪</b>：
 *       那种状态下可能长时间不开世界，判未就绪会让热更永久不工作。</li>
 * </ul>
 *
 * <p><b>开销</b>：一次探测是若干次反射查找，不做堆遍历、不抓线程栈；调用方在就绪后即停止
 * 探测（就绪单向），因此只在启动窗口内被反复调用。</p>
 */
public final class HostReadinessProbe {

	/** 探测结论。 */
	public enum Verdict {
		/** 有明确的生命周期信号表明主循环已经在跑。 */
		READY,
		/** 有明确的生命周期信号表明主循环还没跑起来（宿主仍在启动期）。 */
		NOT_READY,
		/** 取不到任何就绪信号。调用方必须按"已就绪"处理（fail-open）。 */
		UNKNOWN
	}

	/**
	 * 类查找口。生产默认从 {@code RemoldAgent.loadedClassesMap}（宿主加载器已加载类快照）查找，
	 * <b>绝不回退到系统类加载器</b>：system loader 的 classpath 里也有 Minecraft jar，
	 * {@code Class.forName(..., getSystemClassLoader())} 会静默定义一份裸副本
	 * （无 Mixin 织入 / 无 PackageAccessFixer），后续热更若选中或缓存它就会触发
	 * {@code VerifyError: Bad access to protected data}。测试可注入替身，不需要真实 Minecraft。
	 */
	public interface ClassLocator {
		Class<?> find(String className);
	}

	/** 服务端主类（Yarn 与 Mojang 官方映射同名）。 */
	static final String SERVER_CLASS = "net.minecraft.server.MinecraftServer";

	/** 客户端主类：Yarn 用 {@code MinecraftClient}，Mojang 官方映射用 {@code Minecraft}。 */
	static final String[] CLIENT_CLASSES = {
		"net.minecraft.client.MinecraftClient",
		"net.minecraft.client.Minecraft"
	};

	private final ClassLocator locator;

	/** 生产构造：只从 Remold 的宿主类快照探测，绝不使用系统类加载器（避免裸副本）。 */
	public HostReadinessProbe() {
		this(HostReadinessProbe::hostLoadedClass);
	}

	/**
	 * 宿主权威副本查找：只认 {@code loadedClassesMap}（由 {@code ClassCopySelector} 仲裁后的快照）。
	 *
	 * <p>旧实现用 {@code Class.forName(className, false, getSystemClassLoader())}，在 Fabric dev
	 * 里会给 {@code MinecraftServer}/{@code MinecraftClient} 及其方法签名涉及的游戏类各定义一份
	 * {@code AppClassLoader} 裸副本——真机 {@code [DUP-LOAD]} 栈已定位到
	 * {@code HostReadinessProbe.staticInstance -> Class.getDeclaredMethod}。</p>
	 */
	private static Class<?> hostLoadedClass(String className) {
		Class<?> loaded = remold.RemoldAgent.loadedClassesMap.get(className);
		if (loaded != null) {
			return loaded;
		}
		return null;   // 取不到就 UNKNOWN（fail-open 放行），绝不制造裸副本
	}

	/**
	 * @param locator 类查找口；{@code null} 等价于生产默认（系统类加载器）
	 */
	public HostReadinessProbe(ClassLocator locator) {
		this.locator = locator == null ? HostReadinessProbe::hostLoadedClass : locator;
	}

	/**
	 * 探测一次宿主就绪状态。
	 *
	 * <p>本方法<b>不抛异常</b>：探测本身失败等同于"取不到信号"，返回 {@link Verdict#UNKNOWN}。
	 * 抛异常会让门闩按 fail-closed 处理（{@code firstPluginNotReady} 把异常当未就绪），
	 * 那正好是我们最不想发生的结果。</p>
	 *
	 * @return 探测结论，永不为 {@code null}
	 */
	public Verdict probe() {
		try {
			Verdict server = probeServer();
			if (server != null) return server;
			Verdict client = probeClient();
			if (client != null) return client;
		} catch (Throwable ignored) {
			// 探测失败 = 取不到信号，由调用方回退到"按已就绪处理"。
		}
		return Verdict.UNKNOWN;
	}

	/** @return 服务端结论；没有可用信号时返回 {@code null}（继续看客户端） */
	private Verdict probeServer() {
		Class<?> serverClass = find(SERVER_CLASS);
		if (serverClass == null) return null;
		Object server = staticInstance(serverClass, "getServer", "server");
		// 实例还没创建：可能是纯客户端运行（客户端 jar 里也有 MinecraftServer 类），
		// 判"未就绪"会让那种环境永久押住批次，所以这里只报告"没有信号"。
		if (server == null) return null;

		Integer ticks = intValue(server, "getTicks", "getTickCount");
		if (ticks != null) return ticks > 0 ? Verdict.READY : Verdict.NOT_READY;

		Boolean running = booleanValue(server, "isRunning");
		if (running != null) return running ? Verdict.READY : Verdict.NOT_READY;
		return null;
	}

	/** @return 客户端结论；没有可用信号时返回 {@code null}（→ UNKNOWN） */
	private Verdict probeClient() {
		for (String className : CLIENT_CLASSES) {
			Class<?> clientClass = find(className);
			if (clientClass == null) continue;
			Object client = staticInstance(clientClass, "getInstance", "instance");
			if (client == null) return null;

			// 已进入世界：主循环必然早已跑过首个 tick（启动风暴已过）。
			if (fieldValue(client, "world", "level") != null) return Verdict.READY;

			// 停在主菜单/启动界面：不判未就绪（可能长时间不开世界，判未就绪 = 永久饿死批次）。
			return null;
		}
		return null;
	}

	private Class<?> find(String className) {
		try {
			return locator.find(className);
		} catch (Throwable ignored) {
			return null;
		}
	}


	/** 读静态实例：先按静态方法（{@code getServer}/{@code getInstance}），再按静态字段。 */
	private static Object staticInstance(Class<?> owner, String... accessorNames) {
		for (String name : accessorNames) {
			try {
				Method m = owner.getDeclaredMethod(name);
				if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
					m.setAccessible(true);
					Object value = m.invoke(null);
					if (value != null) return value;
				}
			} catch (Throwable ignored) {
			}
			try {
				Field f = owner.getDeclaredField(name);
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					Object value = f.get(null);
					if (value != null) return value;
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/** 读无参数值访问器（int/long 皆可）；取不到返回 {@code null}。 */
	private static Integer intValue(Object target, String... accessorNames) {
		for (String name : accessorNames) {
			try {
				Method m = target.getClass().getMethod(name);
				if (m.getParameterCount() != 0) continue;
				Class<?> rt = m.getReturnType();
				if (rt != int.class && rt != Integer.class && rt != long.class && rt != Long.class) continue;
				Object value = m.invoke(target);
				if (value instanceof Number) return ((Number) value).intValue();
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/** 读无参布尔访问器；取不到返回 {@code null}。 */
	private static Boolean booleanValue(Object target, String... accessorNames) {
		for (String name : accessorNames) {
			try {
				Method m = target.getClass().getMethod(name);
				if (m.getParameterCount() != 0) continue;
				Class<?> rt = m.getReturnType();
				if (rt != boolean.class && rt != Boolean.class) continue;
				Object value = m.invoke(target);
				if (value instanceof Boolean) return (Boolean) value;
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/** 读实例字段（沿继承链找声明处）；取不到返回 {@code null}。 */
	private static Object fieldValue(Object target, String... fieldNames) {
		for (String name : fieldNames) {
			Class<?> c = target.getClass();
			while (c != null) {
				try {
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					return f.get(target);
				} catch (NoSuchFieldException ignored) {
					c = c.getSuperclass();
				} catch (Throwable ignored) {
					break;
				}
			}
		}
		return null;
	}
}

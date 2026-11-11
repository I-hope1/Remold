package remold;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 用 {@link Instrumentation#redefineModule} 在 **premain/agentmain 的第一行**把 Remold 需要的 JDK 内部包
 * 对 {@code ALL-UNNAMED} 打开（opens）并导出（exports），从而不再要求用户手写
 * {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED} 之类的启动参数。
 *
 * <p><b>为什么是"打开 + 导出"两件事</b>：{@code --add-exports} 管的是<b>链接期</b>可见性（读到非导出包的
 * public 类型会抛 {@code IllegalAccessError}），{@code --add-opens} 管的是<b>深反射</b>
 * （{@code setAccessible} 会抛 {@code InaccessibleObjectException}）。两者缺一，行为不同但都会失败，
 * 所以这里对同一组包同时加 export 与 open。</p>
 *
 * <p><b>为什么用反射而不是直接调 API</b>：{@code Module} / {@code redefineModule} 是 Java 9+ 的 API，
 * 而 agent 仍要能在 Java 8（含 DCEVM/TravaJDK 11 之前的运行环境）里被加载。这里全程
 * {@code Class.forName} + {@code Method.invoke}，在 Java 8 上直接短路返回，类本身也不会链接不到
 * {@code java.lang.Module}。</p>
 *
 * <p><b>为什么放在最前面</b>：一旦某个 Remold 类先触碰了 {@code jdk.internal.*}（例如 {@code Reflect}
 * 取 {@code Unsafe}、或 premain 里取 {@code jdk.internal.loader}），类解析就已经失败，之后再打开模块
 * 也来不及。因此本方法必须是 {@code premain}/{@code agentmain} 的第一个动作。</p>
 *
 * <p><b>失败即降级，不改变既有契约</b>：任何一步失败只记一条 warn（例如宿主是 Java 8、或某模块不可
 * 重定义），用户仍然可以按老办法传 {@code --add-exports}/{@code --add-opens}，行为与升级前一致。</p>
 */
public final class ModuleOpener {

	/**
	 * 需要 export + open 的 {@code 模块名 → 包名}。
	 *
	 * <p>清单只覆盖 Remold 自己真正会用的：{@code jdk.internal.misc}（{@code Unsafe}）、
	 * {@code jdk.internal.reflect}（{@code ReflectionFactory}）、{@code jdk.internal.loader}
	 * （{@code ClassLoader} 内部查找）、{@code jdk.internal.access}（{@code SharedSecrets} 通道）、
	 * {@code jdk.internal.vm}（{@code VM} 状态）。{@code java.lang} / {@code java.lang.invoke} /
	 * {@code java.lang.reflect} / {@code sun.nio.ch} 属于历史代码路径与第三方插件会用到的那部分。
	 * {@code sun.misc} 属于 {@code jdk.unsupported} 模块而不是 {@code java.base}，{@code redefineModule}
	 * 会以 {@code IllegalArgumentException: sun.misc not in module} 拒绝，故不在此列（Remold 走的是
	 * {@code jdk.internal.misc.Unsafe}，不需要它）。</p>
	 */
	private static final Map<String, String[]> PACKAGES = new LinkedHashMap<>();

	static {
		PACKAGES.put("java.base", new String[]{
			"jdk.internal.misc",
			"jdk.internal.reflect",
			"jdk.internal.loader",
			"jdk.internal.access",
			"jdk.internal.vm",
			"java.lang",
			"java.lang.invoke",
			"java.lang.reflect",
			"sun.nio.ch",
		});
	}

	private ModuleOpener() {
	}

	/**
	 * 把 {@link #PACKAGES} 里的每个包对 {@code ALL-UNNAMED} 导出并打开。
	 *
	 * @param inst agent 拿到的 {@link Instrumentation}；{@code null} 时直接返回
	 * @return 实际成功处理的 (模块, 包) 数量；{@code 0} 表示不可用（Java 8）或全部失败
	 */
	public static int openForAgent(Instrumentation inst) {
		if (inst == null) {
			return 0;
		}
		Class<?> moduleClass;
		Method redefineModule;
		try {
			moduleClass = Class.forName("java.lang.Module");
			// Instrumentation.redefineModule(Module, Set<Module> extraReads, Map<String,Set<Module>> extraExports,
			//                                 Map<String,Set<Module>> extraOpens, Set<Class<?>> extraUses,
			//                                 Map<Class<?>,List<Class<?>>> extraProvides)
			redefineModule = Instrumentation.class.getMethod("redefineModule", moduleClass, Set.class, Map.class,
				Map.class, Set.class, Map.class);
		} catch (Throwable notModular) {
			// Java 8：没有模块系统，也就不存在 --add-exports/--add-opens 需求。
			return 0;
		}
		int opened = 0;
		for (Map.Entry<String, String[]> entry : PACKAGES.entrySet()) {
			Object module = moduleByName(entry.getKey());
			if (module == null) {
				continue;
			}
			Map<String, Set<Object>> targets = new LinkedHashMap<>();
			// 注意：{@code redefineModule} 对"空目标集合"直接抛 {@code IllegalArgumentException:
			// set of targets is empty}，所以必须显式列出要打开给谁——这里就是"所有我们能拿到的 unnamed
			// module"（agent 自己、系统加载器、线程上下文加载器），等价于用户传 {@code =ALL-UNNAMED}。
			for (String pkg : entry.getValue()) {
				targets.put(pkg, unnamedModules());
			}
			try {
				redefineModule.invoke(inst, module, Collections.emptySet(), targets, targets,
					Collections.emptySet(), Collections.emptyMap());
				opened += entry.getValue().length;
				// 官方 API 只能开给"此刻已存在的" unnamed module；premain 之后才创建的加载器（Knot /
				// ModLauncher / 插件加载器）各有一份新的 unnamed module，仍会 InaccessibleObjectException。
				// 因此再用 Remold 已有的全特权 Lookup 调一次包私有的 implAddExports/implAddOpens，目标写
				// Module.EVERYONE_MODULE（= 对所有人，含将来才出现的 unnamed module）。这是
				// `_libs/compiler.jar` 里 HopeReflect.openModule() 的同一招，但 Remold 的 IMPL_LOOKUP
				// 自举路径与它无关（Reflect.IMPL_LOOKUP，多策略兜底），不需要任何新魔法。
				openToEveryone(module, entry.getValue());
			} catch (Throwable t) {
				RemoldAgent.warn("[Agent] could not export/open " + entry.getKey() + " packages ("
					+ String.join(", ", entry.getValue()) + ") through Instrumentation.redefineModule;"
					+ " pass --add-exports/--add-opens on the command line if a reflective access fails: "
					+ rootMessage(t));
			}
		}
		if (opened > 0) {
			RemoldAgent.info("[Agent] opened " + opened + " JDK-internal package(s) of java.base to ALL-UNNAMED"
				+ " via Instrumentation.redefineModule; --add-exports/--add-opens are no longer required");
		}
		return opened;
	}

	/**
	 * 把若干包对 <b>{@code Module.EVERYONE_MODULE}</b>（"所有人"，含将来才创建的 unnamed module）
	 * export + open。走 {@code Module.implAddExports/implAddOpens} 这两个包私有方法，用
	 * {@link remold.Reflect#IMPL_LOOKUP} 全特权 Lookup 调用。
	 *
	 * <p><b>为什么需要它</b>：{@code Instrumentation.redefineModule} 的 {@code extraExports}/{@code extraOpens}
	 * 只能指定"调用时已存在"的模块集合。宿主加载器（Fabric Knot、Forge ModLauncher）与插件加载器都在
	 * premain 之后才创建，它们各自的 unnamed module 不在那个集合里 —— 实测（JBR-21）：
	 * 只调 {@code redefineModule} 时，系统加载器里的类可以反射 {@code jdk.internal.misc.Unsafe}，
	 * 而 premain 之后新建的加载器里的类仍然抛 {@code InaccessibleObjectException}。
	 * {@code EVERYONE_MODULE} 是 JDK 内部用来表示 {@code =ALL-UNNAMED} 的哨兵模块，一次写入即覆盖未来模块。</p>
	 *
	 * <p><b>失败只 warn</b>：这条是兜底路径，拿不到内部字段（未来 JDK 改名）也不影响
	 * {@code redefineModule} 已经打开的那部分，用户仍可按老办法传 {@code --add-opens}。</p>
	 */
	private static void openToEveryone(Object module, String[] packages) {
		try {
			Class<?> moduleClass = Class.forName("java.lang.Module");
			java.lang.invoke.MethodHandles.Lookup lookup = remold.Reflect.IMPL_LOOKUP;
			Object everyone = lookup.findStaticGetter(moduleClass, "EVERYONE_MODULE", moduleClass).invoke();
			java.lang.invoke.MethodHandle addExports = lookup.findVirtual(moduleClass, "implAddExports",
				java.lang.invoke.MethodType.methodType(void.class, String.class, moduleClass));
			java.lang.invoke.MethodHandle addOpens = lookup.findVirtual(moduleClass, "implAddOpens",
				java.lang.invoke.MethodType.methodType(void.class, String.class, moduleClass));
			for (String pkg : packages) {
				try {
					addExports.invokeWithArguments(module, pkg, everyone);
				} catch (Throwable ignored) {
					// 个别包可能已被其它 agent 打开或不允许导出：不影响 open
				}
				try {
					addOpens.invokeWithArguments(module, pkg, everyone);
				} catch (Throwable t) {
					RemoldAgent.warn("[Agent] could not open " + pkg + " to EVERYONE_MODULE ("
						+ rootMessage(t) + "); classes loaded by class loaders created later may still need"
						+ " --add-opens on the command line");
				}
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Agent] EVERYONE_MODULE fallback unavailable (" + rootMessage(t)
				+ "); only modules existing at agent start are opened");
		}
	}

	/**
	 * 收集"所有 unnamed module"：{@code --add-opens x/y=ALL-UNNAMED} 的等价目标集合。
	 *
	 * <p>三类都要覆盖：agent 自己所在的加载器（{@code -javaagent} 下可能是系统加载器，被
	 * {@code appendToBootstrapClassLoaderSearch} 之后也可能是 bootstrap）、系统加载器、线程上下文加载器
	 * （宿主 Knot / ModLauncher 的那份）。拿不到的（例如加载器为 null 的 bootstrap 场景）跳过。</p>
	 */
	private static Set<Object> unnamedModules() {
		Set<Object> modules = new java.util.LinkedHashSet<>();
		addUnnamed(modules, ModuleOpener.class.getModule());
		addUnnamed(modules, unnamedOf(ClassLoader.getSystemClassLoader()));
		ClassLoader context = null;
		try {
			context = Thread.currentThread().getContextClassLoader();
		} catch (Throwable ignored) {
			// 安全管理者拒绝时忽略
		}
		addUnnamed(modules, unnamedOf(context));
		return modules;
	}

	private static Object unnamedOf(ClassLoader loader) {
		if (loader == null) {
			return null;
		}
		try {
			return ClassLoader.class.getMethod("getUnnamedModule").invoke(loader);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void addUnnamed(Set<Object> modules, Object module) {
		if (module == null) {
			return;
		}
		try {
			Object named = Class.forName("java.lang.Module").getMethod("isNamed").invoke(module);
			if (Boolean.FALSE.equals(named)) {
				modules.add(module);
			}
		} catch (Throwable ignored) {
			// 取不到 isNamed 就当作不可用
		}
	}

	/**
	 * 按名字取一个已加载模块。
	 *
	 * <p>用"模块内某个已加载类的 {@code Class.getModule()}"取值，而不是遍历 {@code ModuleLayer}：
	 * 后者拿到的是 {@code ResolvedModule}，不是 {@code Module}，还得再经 {@code reference()} 转一手，
	 * 反射路径更长也更容易在受限环境里失败。这里只登记 {@code java.base}，因此一个代表类就够。</p>
	 */
	private static Object moduleByName(String name) {
		if (!"java.base".equals(name)) {
			return null; // 目前只打开 java.base；要扩展就为每个模块补一个代表类
		}
		try {
			return Class.class.getMethod("getModule").invoke(Object.class);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		return cur.getClass().getSimpleName() + (cur.getMessage() == null ? "" : ": " + cur.getMessage());
	}
}

package remold.mctoast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import remold.RemoldAgent;

/**
 * 零编译期 Minecraft 依赖的 Toast 展示器：经由宿主类加载器动态代理 {@code Toast} 接口并派发到渲染线程。
 *
 * <p><b>为什么使用 {@link Proxy} 而不是引入 Minecraft 依赖</b>：本插件随 Agent 运行于系统类加载器，
 * 必须跨 MC 版本（1.21.1 与 1.21.4+ 的 {@code Toast} 签名变化）以及跨映射（Yarn / Mojmap）工作。
 * 通过只在 {@code RemoldAgent.loadedClassesMap} 查找宿主已加载的客户端主类（绝不调用
 * {@code Class.forName(..., systemClassLoader)} 以免触发 {@code [DUP-LOAD]} 双份类加载），
 * 卡片渲染与刷新完全解耦出模组代码。</p>
 *
 * <p><b>fail-open 契约</b>：服务端环境、映射变化或任何反射异常至多打印一次告警并静默降级，
 * 绝不抛回 Remold 主热更管线。</p>
 */
public final class McToastPresenter {

	/** 从宿主权威类快照按名查找已加载类的接口；生产默认读取 {@code RemoldAgent.loadedClassesMap}。 */
	public interface ClassLocator {
		Class<?> find(String className);
	}

	private static final String[] CLIENT_CLASSES = {
		"net.minecraft.client.MinecraftClient",
		"net.minecraft.client.Minecraft"
	};

	private static final String[] TOAST_INTERFACES = {
		"net.minecraft.client.toast.Toast",
		"net.minecraft.client.gui.components.toasts.Toast"
	};

	private static final String[] VISIBILITY_CLASSES = {
		"net.minecraft.client.toast.Toast$Visibility",
		"net.minecraft.client.gui.components.toasts.Toast$Visibility"
	};

	static final int WIDTH = 224;
	static final int HEIGHT = 64;
	static final int ACCENT_WIDTH = 3;
	static final long DEFAULT_DISPLAY_MS = 4000L;

	private static final int COLOR_BACKGROUND = 0xF0101218;
	private static final int COLOR_BORDER = 0xFF3A4356;
	private static final int COLOR_ACCENT = 0xFF55D6BE;
	private static final int COLOR_TITLE = 0xFFEAF2FF;
	private static final int COLOR_TEXT = 0xFFB9C4D6;
	private static final int COLOR_MIXIN = 0xFF9CDCFE;
	private static final int COLOR_INITFIX = 0xFFF2CC60;

	/** 供 {@code ToastManager.getToast} 定位活跃/排队卡片的唯一类型键。 */
	static final Object TYPE_TOKEN = new Object();

	private final ClassLocator locator;
	private final LongSupplier clockMs;
	private final Consumer<String> infoLogger;
	private final Consumer<String> warnLogger;

	private final Object bindLock = new Object();
	private final AtomicBoolean failureLogged = new AtomicBoolean(false);
	private volatile HostBinding binding;
	private volatile boolean permanentlyUnavailable;

	public McToastPresenter() {
		this(RemoldAgent.loadedClassesMap::get, () -> System.nanoTime() / 1_000_000L,
			RemoldAgent::info, RemoldAgent::warn);
	}

	McToastPresenter(ClassLocator locator, LongSupplier clockMs,
	                 Consumer<String> infoLogger, Consumer<String> warnLogger) {
		this.locator = locator == null ? RemoldAgent.loadedClassesMap::get : locator;
		this.clockMs = clockMs == null ? () -> System.nanoTime() / 1_000_000L : clockMs;
		this.infoLogger = infoLogger == null ? s -> {} : infoLogger;
		this.warnLogger = warnLogger == null ? s -> {} : warnLogger;
	}

	/**
	 * 在 Minecraft 客户端主渲染线程上展示或就地刷新一张 HotSwap Toast 卡片。
	 * 非客户端环境、API 不可用或渲染异常均 fail-open 返回。
	 */
	public void show(ToastModel model) {
		if (model == null || permanentlyUnavailable) {
			return;
		}
		try {
			HostBinding resolved = ensureBound();
			if (resolved == null) {
				return;
			}
			Object client = resolved.clientInstance();
			if (client == null) {
				return;
			}
			resolved.executeOnRenderThread(client, () -> presentSafely(resolved, model));
		} catch (Throwable t) {
			warnOnce("Failed to schedule HotSwap Toast: " + t);
		}
	}

	private void presentSafely(HostBinding resolved, ToastModel model) {
		try {
			Object client = resolved.clientInstance();
			if (client == null) {
				return;
			}
			Object toastManager = resolved.getToastManager(client);
			if (toastManager == null) {
				return;
			}
			Object existing = resolved.findExistingToast(toastManager);
			if (existing != null && Proxy.isProxyClass(existing.getClass())) {
				InvocationHandler handler = Proxy.getInvocationHandler(existing);
				if (handler instanceof ToastInvocationHandler) {
					((ToastInvocationHandler) handler).refresh(model);
					return;
				}
			}
			ToastInvocationHandler handler = new ToastInvocationHandler(resolved, model);
			Object toastProxy = Proxy.newProxyInstance(resolved.hostClassLoader,
				new Class<?>[] { resolved.toastInterface }, handler);
			resolved.addToast(toastManager, toastProxy);
		} catch (Throwable t) {
			warnOnce("Failed to present HotSwap Toast: " + t);
		}
	}

	private HostBinding ensureBound() {
		HostBinding current = this.binding;
		if (current != null) {
			return current;
		}
		synchronized (bindLock) {
			if (this.binding != null) {
				return this.binding;
			}
			if (permanentlyUnavailable) {
				return null;
			}
			Class<?> clientClass = findClientClass();
			if (clientClass == null) {
				// 纯服务端或客户端尚未加载：先不锁死，等客户端类确实出现后再绑定。
				return null;
			}
			if (findClientInstance(clientClass) == null) {
				// 主类已加载但 singleton 尚未构造完成：等下次批次再试。
				return null;
			}
			try {
				HostBinding created = HostBinding.resolve(clientClass);
				this.binding = created;
				infoLogger.accept("[mc-toast] Bound Minecraft client Toast presenter ("
					+ created.toastInterface.getName() + ")");
				return created;
			} catch (Throwable t) {
				permanentlyUnavailable = true;
				warnOnce("Minecraft Toast API incompatible; disabling HotSwap Toast: " + t.getMessage());
				return null;
			}
		}
	}

	private Class<?> findClientClass() {
		for (String name : CLIENT_CLASSES) {
			Class<?> found = locator.find(name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private void warnOnce(String message) {
		if (failureLogged.compareAndSet(false, true)) {
			warnLogger.accept("[mc-toast] " + message);
		}
	}

	private static Object findClientInstance(Class<?> clientClass) {
		Object byMethod = invokeStaticNoArg(clientClass, "getInstance", "getMinecraft");
		if (byMethod != null) {
			return byMethod;
		}
		return readStaticField(clientClass, "instance", "INSTANCE");
	}

	private static Object invokeStaticNoArg(Class<?> owner, String... names) {
		for (String name : names) {
			try {
				Method m = owner.getMethod(name);
				m.setAccessible(true);
				Object value = m.invoke(null);
				if (value != null) {
					return value;
				}
			} catch (Throwable ignored) {
				// 尝试下一个候选名
			}
		}
		return null;
	}

	private static Object readStaticField(Class<?> owner, String... names) {
		for (String name : names) {
			for (Class<?> cur = owner; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
				try {
					Field f = cur.getDeclaredField(name);
					f.setAccessible(true);
					Object value = f.get(null);
					if (value != null) {
						return value;
					}
				} catch (Throwable ignored) {
					// 尝试父类或下一个候选名
				}
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> owner, String[] names, Class<?>... paramTypes) {
		for (String name : names) {
			try {
				Method m = owner.getMethod(name, paramTypes);
				m.setAccessible(true);
				return m;
			} catch (Throwable ignored) {
				// 尝试下一个候选名
			}
		}
		return null;
	}

	private static Field findField(Class<?> owner, String... names) {
		for (String name : names) {
			for (Class<?> cur = owner; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
				try {
					Field f = cur.getDeclaredField(name);
					f.setAccessible(true);
					return f;
				} catch (Throwable ignored) {
					// 尝试父类或下一个候选名
				}
			}
		}
		return null;
	}

	private static Class<?> loadFromHost(ClassLoader hostCl, String... names) {
		for (String name : names) {
			try {
				return Class.forName(name, false, hostCl);
			} catch (Throwable ignored) {
				// 尝试下一个候选名
			}
		}
		return null;
	}

	private static final class HostBinding {
		final ClassLoader hostClassLoader;
		final Class<?> clientClass;
		final Class<?> toastInterface;
		final Object showVisibility;
		final Object hideVisibility;

		private final Method executeMethod;
		private final Method toastManagerMethod;
		private final Field toastManagerField;
		private final Method getToastMethod;
		private final Method addToastMethod;
		private final Method displayTimeMultiplierMethod;
		private final Method fontMethod;
		private final Field fontField;

		private volatile DrawApi drawApi;
		private volatile FontApi fontApi;

		private HostBinding(ClassLoader hostClassLoader, Class<?> clientClass, Class<?> toastInterface,
		                    Object showVisibility, Object hideVisibility,
		                    Method executeMethod, Method toastManagerMethod, Field toastManagerField,
		                    Method getToastMethod, Method addToastMethod, Method displayTimeMultiplierMethod,
		                    Method fontMethod, Field fontField) {
			this.hostClassLoader = hostClassLoader;
			this.clientClass = clientClass;
			this.toastInterface = toastInterface;
			this.showVisibility = showVisibility;
			this.hideVisibility = hideVisibility;
			this.executeMethod = executeMethod;
			this.toastManagerMethod = toastManagerMethod;
			this.toastManagerField = toastManagerField;
			this.getToastMethod = getToastMethod;
			this.addToastMethod = addToastMethod;
			this.displayTimeMultiplierMethod = displayTimeMultiplierMethod;
			this.fontMethod = fontMethod;
			this.fontField = fontField;
		}

		static HostBinding resolve(Class<?> clientClass) {
			ClassLoader hostCl = clientClass.getClassLoader();
			Method tmMethod = findMethod(clientClass, new String[] { "getToastManager", "getToasts" });
			Field tmField = findField(clientClass, "toastManager", "toasts", "guiToast");
			Class<?> tmClass = tmMethod != null ? tmMethod.getReturnType()
				: (tmField != null ? tmField.getType() : null);

			Class<?> toastIfc = loadFromHost(hostCl, TOAST_INTERFACES);
			if (toastIfc == null && tmClass != null) {
				toastIfc = inferToastInterface(tmClass);
			}
			if (toastIfc == null || !toastIfc.isInterface()) {
				throw new IllegalStateException("Toast interface not found or not an interface");
			}

			Class<?> visClass = inferVisibilityClass(hostCl, toastIfc);
			Object show = enumConstant(visClass, "SHOW");
			Object hide = enumConstant(visClass, "HIDE");
			if (show == null || hide == null) {
				throw new IllegalStateException("Toast Visibility enum SHOW/HIDE not found");
			}

			if (tmClass == null) {
				throw new IllegalStateException("ToastManager accessor not found on client class");
			}
			Method addMethod = findMethod(tmClass, new String[] { "add", "addToast" }, toastIfc);
			if (addMethod == null) {
				throw new IllegalStateException("ToastManager.add(Toast) method not found");
			}
			Method getToast = findMethod(tmClass, new String[] { "getToast" }, Class.class, Object.class);
			Method multiplier = findMultiplierMethod(tmClass);
			Method execMethod = findMethod(clientClass, new String[] { "execute" }, Runnable.class);
			Method fMethod = findMethod(clientClass, new String[] { "getTextRenderer", "getFont" });
			Field fField = findField(clientClass, "textRenderer", "font");

			return new HostBinding(hostCl, clientClass, toastIfc, show, hide,
				execMethod, tmMethod, tmField, getToast, addMethod, multiplier, fMethod, fField);
		}

		private static Class<?> inferToastInterface(Class<?> tmClass) {
			for (Method m : tmClass.getMethods()) {
				if (("add".equals(m.getName()) || "addToast".equals(m.getName()))
					&& m.getParameterCount() == 1 && m.getParameterTypes()[0].isInterface()) {
					return m.getParameterTypes()[0];
				}
			}
			return null;
		}

		private static Class<?> inferVisibilityClass(ClassLoader hostCl, Class<?> toastIfc) {
			Class<?> found = loadFromHost(hostCl, VISIBILITY_CLASSES);
			if (found != null && found.isEnum()) {
				return found;
			}
			for (Method m : toastIfc.getMethods()) {
				Class<?> rt = m.getReturnType();
				if (rt.isEnum() && enumConstant(rt, "SHOW") != null && enumConstant(rt, "HIDE") != null) {
					return rt;
				}
			}
			return null;
		}

		private static Object enumConstant(Class<?> enumClass, String name) {
			if (enumClass == null || !enumClass.isEnum()) {
				return null;
			}
			Object[] constants = enumClass.getEnumConstants();
			if (constants == null) {
				return null;
			}
			for (Object c : constants) {
				if (c instanceof Enum<?> && name.equals(((Enum<?>) c).name())) {
					return c;
				}
			}
			return null;
		}

		private static Method findMultiplierMethod(Class<?> tmClass) {
			Method exact = findMethod(tmClass, new String[] { "getNotificationDisplayTimeMultiplier" });
			if (exact != null) {
				return exact;
			}
			for (Method m : tmClass.getMethods()) {
				if (m.getParameterCount() == 0
					&& (m.getReturnType() == double.class || m.getReturnType() == float.class)
					&& m.getName().contains("NotificationDisplayTime")) {
					m.setAccessible(true);
					return m;
				}
			}
			return null;
		}

		Object clientInstance() {
			return findClientInstance(clientClass);
		}

		void executeOnRenderThread(Object client, Runnable task) throws Exception {
			if (client instanceof Executor) {
				((Executor) client).execute(task);
				return;
			}
			if (executeMethod != null) {
				executeMethod.invoke(client, task);
				return;
			}
			task.run();
		}

		Object getToastManager(Object client) throws Exception {
			if (toastManagerMethod != null) {
				return toastManagerMethod.invoke(client);
			}
			if (toastManagerField != null) {
				return toastManagerField.get(client);
			}
			return null;
		}

		Object findExistingToast(Object toastManager) {
			if (getToastMethod == null) {
				return null;
			}
			try {
				return getToastMethod.invoke(toastManager, toastInterface, TYPE_TOKEN);
			} catch (Throwable ignored) {
				return null;
			}
		}

		void addToast(Object toastManager, Object toastProxy) throws Exception {
			addToastMethod.invoke(toastManager, toastProxy);
		}

		double displayTimeMultiplier(Object toastManager) {
			if (toastManager == null || displayTimeMultiplierMethod == null) {
				return 1.0d;
			}
			try {
				Object v = displayTimeMultiplierMethod.invoke(toastManager);
				if (v instanceof Number) {
					double d = ((Number) v).doubleValue();
					return d > 0.0d ? d : 1.0d;
				}
			} catch (Throwable ignored) {
				// 保持默认值 1.0
			}
			return 1.0d;
		}

		Object resolveFont(Object client) {
			if (client == null) {
				return null;
			}
			try {
				if (fontMethod != null) {
					Object v = fontMethod.invoke(client);
					if (v != null) {
						return v;
					}
				}
				if (fontField != null) {
					return fontField.get(client);
				}
			} catch (Throwable ignored) {
				// 取不到返回 null
			}
			return null;
		}

		DrawApi drawApi(Class<?> contextClass, Class<?> fontClass) {
			DrawApi api = this.drawApi;
			if (api != null) {
				return api;
			}
			Method fill = findMethod(contextClass, new String[] { "fill" },
				int.class, int.class, int.class, int.class, int.class);
			Method drawText = findMethod(contextClass, new String[] { "drawText", "drawString" },
				fontClass, String.class, int.class, int.class, int.class, boolean.class);
			boolean drawTextHasShadow = true;
			if (drawText == null) {
				drawText = findAssignableDrawText(contextClass, fontClass, true);
			}
			if (drawText == null) {
				drawText = findMethod(contextClass, new String[] { "drawText", "drawString" },
					fontClass, String.class, int.class, int.class, int.class);
				if (drawText == null) {
					drawText = findAssignableDrawText(contextClass, fontClass, false);
				}
				drawTextHasShadow = false;
			}
			api = new DrawApi(fill, drawText, drawTextHasShadow);
			this.drawApi = api;
			return api;
		}

		private static Method findAssignableDrawText(Class<?> contextClass, Class<?> fontClass, boolean withShadow) {
			int expectedParams = withShadow ? 6 : 5;
			for (Method m : contextClass.getMethods()) {
				if ((!"drawText".equals(m.getName()) && !"drawString".equals(m.getName()))
					|| m.getParameterCount() != expectedParams) {
					continue;
				}
				Class<?>[] p = m.getParameterTypes();
				if (p[0].isAssignableFrom(fontClass)
					&& p[1] == String.class
					&& p[2] == int.class && p[3] == int.class && p[4] == int.class
					&& (!withShadow || p[5] == boolean.class)) {
					m.setAccessible(true);
					return m;
				}
			}
			return null;
		}

		FontApi fontApi(Class<?> fontClass) {
			FontApi api = this.fontApi;
			if (api != null) {
				return api;
			}
			Method width = findMethod(fontClass, new String[] { "getWidth", "width" }, String.class);
			Method trim = findMethod(fontClass, new String[] { "trimToWidth", "plainSubstrByWidth" },
				String.class, int.class);
			api = new FontApi(width, trim);
			this.fontApi = api;
			return api;
		}
	}

	private static final class DrawApi {
		final Method fill;
		final Method drawText;
		final boolean drawTextHasShadow;

		DrawApi(Method fill, Method drawText, boolean drawTextHasShadow) {
			this.fill = fill;
			this.drawText = drawText;
			this.drawTextHasShadow = drawTextHasShadow;
		}

		void fill(Object ctx, int x1, int y1, int x2, int y2, int color) throws Exception {
			if (fill != null) {
				fill.invoke(ctx, x1, y1, x2, y2, color);
			}
		}

		void drawText(Object ctx, Object font, String text, int x, int y, int color) throws Exception {
			if (drawText == null || text == null) {
				return;
			}
			if (drawTextHasShadow) {
				drawText.invoke(ctx, font, text, x, y, color, false);
			} else {
				drawText.invoke(ctx, font, text, x, y, color);
			}
		}
	}

	private static final class FontApi {
		final Method getWidth;
		final Method trimToWidth;

		FontApi(Method getWidth, Method trimToWidth) {
			this.getWidth = getWidth;
			this.trimToWidth = trimToWidth;
		}

		String trim(Object font, String text, int maxWidth) {
			if (text == null || font == null || trimToWidth == null) {
				return text;
			}
			try {
				if (getWidth != null) {
					Object w = getWidth.invoke(font, text);
					if (w instanceof Number && ((Number) w).intValue() <= maxWidth) {
						return text;
					}
				}
				Object trimmed = trimToWidth.invoke(font, text, maxWidth);
				return trimmed instanceof String ? (String) trimmed : text;
			} catch (Throwable ignored) {
				return text;
			}
		}
	}

	private final class ToastInvocationHandler implements InvocationHandler {
		private final HostBinding binding;
		private volatile ToastModel model;
		private volatile long shownAtMillis;
		private volatile boolean timerStarted;
		private volatile Object visibility;

		ToastInvocationHandler(HostBinding binding, ToastModel model) {
			this.binding = binding;
			this.model = model;
			this.visibility = binding.showVisibility;
		}

		void refresh(ToastModel newModel) {
			if (newModel == null) {
				return;
			}
			this.model = newModel;
			this.timerStarted = false;
			this.visibility = binding.showVisibility;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			if ("equals".equals(name) && method.getParameterCount() == 1) {
				return proxy == args[0];
			}
			if ("hashCode".equals(name) && method.getParameterCount() == 0) {
				return System.identityHashCode(proxy);
			}
			if ("toString".equals(name) && method.getParameterCount() == 0) {
				return "RemoldMcToast[" + model + "]";
			}
			if (("getWidth".equals(name) || "width".equals(name)) && method.getParameterCount() == 0) {
				return WIDTH;
			}
			if (("getHeight".equals(name) || "height".equals(name)) && method.getParameterCount() == 0) {
				return HEIGHT;
			}
			if (("getRequiredSpaceCount".equals(name) || "slotCount".equals(name))
				&& method.getParameterCount() == 0) {
				return 2;
			}
			if (("getType".equals(name) || "getToken".equals(name)) && method.getParameterCount() == 0) {
				return TYPE_TOKEN;
			}
			if (("getVisibility".equals(name) || "getWantedVisibility".equals(name))
				&& method.getParameterCount() == 0) {
				return visibility;
			}
			if ("update".equals(name)) {
				Object tm = args != null && args.length > 0 ? args[0] : null;
				this.visibility = updateVisibility(tm);
				return null;
			}
			if (("draw".equals(name) || "render".equals(name)) && args != null && args.length >= 2) {
				if (method.getReturnType() != void.class) {
					// 1.21.1 契约：draw(DrawContext, ToastManager, long) -> Visibility
					Object vis = updateVisibility(args[1]);
					this.visibility = vis;
					if (vis == binding.hideVisibility) {
						return vis;
					}
					Object font = binding.resolveFont(binding.clientInstance());
					if (args[0] != null && font != null) {
						renderCard(args[0], font, this.model);
					}
					return vis;
				}
				// 1.21.4+ 契约：draw(DrawContext, TextRenderer, long) -> void（计时由 update 维护）
				Object font = args[1] != null ? args[1] : binding.resolveFont(binding.clientInstance());
				if (args[0] != null && font != null) {
					renderCard(args[0], font, this.model);
				}
				return null;
			}
			return null;
		}

		private Object updateVisibility(Object toastManager) {
			long now = clockMs.getAsLong();
			if (!timerStarted) {
				shownAtMillis = now;
				timerStarted = true;
			}
			long displayMs = (long) (DEFAULT_DISPLAY_MS * binding.displayTimeMultiplier(toastManager));
			if (now - shownAtMillis >= displayMs) {
				return binding.hideVisibility;
			}
			return binding.showVisibility;
		}

		private void renderCard(Object drawContext, Object font, ToastModel current) throws Exception {
			DrawApi draw = binding.drawApi(drawContext.getClass(), font.getClass());
			FontApi fontApi = binding.fontApi(font.getClass());

			draw.fill(drawContext, 0, 0, WIDTH, HEIGHT, COLOR_BACKGROUND);
			// 四条 1px 矩形边框：比 drawBorder 更通用，不依赖 1.21 版本之间的边框辅助方法签名
			draw.fill(drawContext, 0, 0, WIDTH, 1, COLOR_BORDER);
			draw.fill(drawContext, 0, HEIGHT - 1, WIDTH, HEIGHT, COLOR_BORDER);
			draw.fill(drawContext, 0, 0, 1, HEIGHT, COLOR_BORDER);
			draw.fill(drawContext, WIDTH - 1, 0, WIDTH, HEIGHT, COLOR_BORDER);
			draw.fill(drawContext, 0, 0, ACCENT_WIDTH, HEIGHT, COLOR_ACCENT);

			draw.drawText(drawContext, font, fontApi.trim(font, current.getTitle(), WIDTH - 20),
				12, 8, COLOR_TITLE);

			List<String> lines = current.getSummaryLines();
			for (int i = 0; i < lines.size() && i < ToastModel.MAX_SUMMARY_LINES; i++) {
				String line = lines.get(i);
				draw.drawText(drawContext, font, fontApi.trim(font, line, WIDTH - 20),
					12, 24 + i * 12, colorFor(line));
			}
		}

		private int colorFor(String line) {
			if (line.startsWith("Mixin")) {
				return COLOR_MIXIN;
			}
			if (line.startsWith("InitFix")) {
				return COLOR_INITFIX;
			}
			return COLOR_TEXT;
		}
	}
}

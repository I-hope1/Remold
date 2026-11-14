package remold.mctoast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import remold.HotswapBatchReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McToastPresenterTest {

	@Test
	void bindsYarn1211DrawsRefreshesInPlaceAndAutoHides() throws Exception {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.client.font.TextRenderer",
			"package net.minecraft.client.font;\n"
			+ "public class TextRenderer {\n"
			+ "  public int getWidth(String text) { return text.length() * 6; }\n"
			+ "  public String trimToWidth(String text, int maxWidth) {\n"
			+ "    int maxChars = Math.max(0, maxWidth / 6);\n"
			+ "    return text.length() <= maxChars ? text : text.substring(0, maxChars);\n"
			+ "  }\n"
			+ "}\n");
		sources.put("net.minecraft.client.gui.DrawContext",
			"package net.minecraft.client.gui;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "import net.minecraft.client.font.TextRenderer;\n"
			+ "public class DrawContext {\n"
			+ "  public final List<String> ops = new ArrayList<>();\n"
			+ "  public void fill(int x1, int y1, int x2, int y2, int color) {\n"
			+ "    ops.add(\"fill:\" + x1 + \",\" + y1 + \",\" + x2 + \",\" + y2);\n"
			+ "  }\n"
			+ "  public int drawText(TextRenderer tr, String text, int x, int y, int color, boolean shadow) {\n"
			+ "    ops.add(\"text:\" + text);\n"
			+ "    return 0;\n"
			+ "  }\n"
			+ "}\n");
		sources.put("net.minecraft.client.toast.ToastManager",
			"package net.minecraft.client.toast;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "public class ToastManager {\n"
			+ "  public final List<Toast> queue = new ArrayList<>();\n"
			+ "  public double displayMultiplier = 1.0d;\n"
			+ "  public void add(Toast toast) { queue.add(toast); }\n"
			+ "  @SuppressWarnings(\"unchecked\")\n"
			+ "  public <T extends Toast> T getToast(Class<? extends T> type, Object token) {\n"
			+ "    for (Toast t : queue) {\n"
			+ "      if (type.isAssignableFrom(t.getClass()) && t.getType().equals(token)) return (T) t;\n"
			+ "    }\n"
			+ "    return null;\n"
			+ "  }\n"
			+ "  public double getNotificationDisplayTimeMultiplier() { return displayMultiplier; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.toast.Toast",
			"package net.minecraft.client.toast;\n"
			+ "import net.minecraft.client.gui.DrawContext;\n"
			+ "public interface Toast {\n"
			+ "  enum Visibility { SHOW, HIDE }\n"
			+ "  Visibility draw(DrawContext context, ToastManager manager, long startTime);\n"
			+ "  default Object getType() { return new Object(); }\n"
			+ "  default int getWidth() { return 160; }\n"
			+ "  default int getHeight() { return 32; }\n"
			+ "  default int getRequiredSpaceCount() { return 1; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.MinecraftClient",
			"package net.minecraft.client;\n"
			+ "import java.util.concurrent.Executor;\n"
			+ "import net.minecraft.client.font.TextRenderer;\n"
			+ "import net.minecraft.client.toast.ToastManager;\n"
			+ "public class MinecraftClient implements Executor {\n"
			+ "  public static MinecraftClient INSTANCE = new MinecraftClient();\n"
			+ "  public static MinecraftClient getInstance() { return INSTANCE; }\n"
			+ "  public final TextRenderer textRenderer = new TextRenderer();\n"
			+ "  private final ToastManager toastManager = new ToastManager();\n"
			+ "  public ToastManager getToastManager() { return toastManager; }\n"
			+ "  @Override public void execute(Runnable r) { r.run(); }\n"
			+ "}\n");

		ClassLoader hostCl = compileIsolated(sources);
		Class<?> mcClass = hostCl.loadClass("net.minecraft.client.MinecraftClient");
		Object mc = mcClass.getMethod("getInstance").invoke(null);
		Object tm = mcClass.getMethod("getToastManager").invoke(mc);

		AtomicLong clock = new AtomicLong(1000L);
		List<String> warns = new ArrayList<>();
		McToastPresenter presenter = new McToastPresenter(
			name -> name.equals("net.minecraft.client.MinecraftClient") ? mcClass : null,
			clock::get, s -> {}, warns::add);

		presenter.show(ToastModel.from(new HotswapBatchReport(25L,
			Collections.singletonList("com.example.FirstClass"), null, null)));

		@SuppressWarnings("unchecked")
		List<Object> queue = (List<Object>) tm.getClass().getField("queue").get(tm);
		assertEquals(1, queue.size(), "First hot reload should enqueue one Toast");
		Object toast = queue.get(0);
		assertEquals(McToastPresenter.WIDTH, toast.getClass().getMethod("getWidth").invoke(toast));
		assertEquals(McToastPresenter.HEIGHT, toast.getClass().getMethod("getHeight").invoke(toast));

		Class<?> drawCtxClass = hostCl.loadClass("net.minecraft.client.gui.DrawContext");
		Class<?> tmClass = hostCl.loadClass("net.minecraft.client.toast.ToastManager");
		Class<?> toastIfc = hostCl.loadClass("net.minecraft.client.toast.Toast");
		Object drawCtx = drawCtxClass.getConstructor().newInstance();
		Object vis1 = toastIfc.getMethod("draw", drawCtxClass, tmClass, long.class)
			.invoke(toast, drawCtx, tm, 0L);
		assertEquals("SHOW", vis1.toString());

		@SuppressWarnings("unchecked")
		List<String> ops1 = (List<String>) drawCtxClass.getField("ops").get(drawCtx);
		assertTrue(ops1.contains("text:[HotSwap] 25 ms"));
		assertTrue(ops1.contains("text:1 class reloaded: FirstClass"));

		// Advance clock past 3.5s, refresh with second batch -> updates in place & resets timer
		clock.set(4500L);
		presenter.show(ToastModel.from(new HotswapBatchReport(60L,
			Collections.singletonList("com.example.SecondClass"), null, null)));
		assertEquals(1, queue.size(), "Second hot reload must refresh existing Toast in place");

		clock.set(5000L);
		Object drawCtx2 = drawCtxClass.getConstructor().newInstance();
		Object vis2 = toastIfc.getMethod("draw", drawCtxClass, tmClass, long.class)
			.invoke(toast, drawCtx2, tm, 0L);
		assertEquals("SHOW", vis2.toString());
		@SuppressWarnings("unchecked")
		List<String> ops2 = (List<String>) drawCtxClass.getField("ops").get(drawCtx2);
		assertTrue(ops2.contains("text:[HotSwap] 60 ms"));
		assertTrue(ops2.contains("text:1 class reloaded: SecondClass"));

		// Timer started on draw at 5000ms, so 8600ms (3600ms elapsed) is still SHOW; 9000ms (4000ms elapsed) -> HIDE
		clock.set(8600L);
		Object drawCtx3 = drawCtxClass.getConstructor().newInstance();
		Object visStillShown = toastIfc.getMethod("draw", drawCtxClass, tmClass, long.class)
			.invoke(toast, drawCtx3, tm, 0L);
		assertEquals("SHOW", visStillShown.toString());

		clock.set(9000L);
		Object drawCtx4 = drawCtxClass.getConstructor().newInstance();
		Object vis3 = toastIfc.getMethod("draw", drawCtxClass, tmClass, long.class)
			.invoke(toast, drawCtx4, tm, 0L);
		assertEquals("HIDE", vis3.toString());
		assertTrue(warns.isEmpty(), "Unexpected warnings: " + warns);
	}

	@Test
	void bindsYarn1214SplitUpdateAndDraw() throws Exception {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.client.font.TextRenderer",
			"package net.minecraft.client.font;\n"
			+ "public class TextRenderer {\n"
			+ "  public int getWidth(String s) { return s.length() * 6; }\n"
			+ "  public String trimToWidth(String s, int max) { return s; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.gui.DrawContext",
			"package net.minecraft.client.gui;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "import net.minecraft.client.font.TextRenderer;\n"
			+ "public class DrawContext {\n"
			+ "  public final List<String> ops = new ArrayList<>();\n"
			+ "  public void fill(int x1, int y1, int x2, int y2, int color) {}\n"
			+ "  public void drawText(TextRenderer tr, String text, int x, int y, int color, boolean shadow) {\n"
			+ "    ops.add(text);\n"
			+ "  }\n"
			+ "}\n");
		sources.put("net.minecraft.client.toast.ToastManager",
			"package net.minecraft.client.toast;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "public class ToastManager {\n"
			+ "  public final List<Toast> queue = new ArrayList<>();\n"
			+ "  public void add(Toast toast) { queue.add(toast); }\n"
			+ "  @SuppressWarnings(\"unchecked\")\n"
			+ "  public <T extends Toast> T getToast(Class<? extends T> type, Object token) {\n"
			+ "    for (Toast t : queue) if (t.getType().equals(token)) return (T) t;\n"
			+ "    return null;\n"
			+ "  }\n"
			+ "  public double getNotificationDisplayTimeMultiplier() { return 0.5d; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.toast.Toast",
			"package net.minecraft.client.toast;\n"
			+ "import net.minecraft.client.font.TextRenderer;\n"
			+ "import net.minecraft.client.gui.DrawContext;\n"
			+ "public interface Toast {\n"
			+ "  enum Visibility { SHOW, HIDE }\n"
			+ "  Visibility getVisibility();\n"
			+ "  void update(ToastManager manager, long time);\n"
			+ "  void draw(DrawContext context, TextRenderer textRenderer, long startTime);\n"
			+ "  default Object getType() { return new Object(); }\n"
			+ "}\n");
		sources.put("net.minecraft.client.MinecraftClient",
			"package net.minecraft.client;\n"
			+ "import net.minecraft.client.toast.ToastManager;\n"
			+ "public class MinecraftClient {\n"
			+ "  private static final MinecraftClient INST = new MinecraftClient();\n"
			+ "  public static MinecraftClient getInstance() { return INST; }\n"
			+ "  private final ToastManager tm = new ToastManager();\n"
			+ "  public ToastManager getToastManager() { return tm; }\n"
			+ "  public void execute(Runnable r) { r.run(); }\n"
			+ "}\n");

		ClassLoader hostCl = compileIsolated(sources);
		Class<?> mcClass = hostCl.loadClass("net.minecraft.client.MinecraftClient");
		Object mc = mcClass.getMethod("getInstance").invoke(null);
		Object tm = mcClass.getMethod("getToastManager").invoke(mc);

		AtomicLong clock = new AtomicLong(10_000L);
		McToastPresenter presenter = new McToastPresenter(
			name -> name.equals("net.minecraft.client.MinecraftClient") ? mcClass : null,
			clock::get, s -> {}, s -> {});

		presenter.show(ToastModel.from(new HotswapBatchReport(12L,
			Collections.singletonList("com.example.Screen"), null, null)));

		@SuppressWarnings("unchecked")
		List<Object> queue = (List<Object>) tm.getClass().getField("queue").get(tm);
		assertEquals(1, queue.size());
		Object toast = queue.get(0);

		Class<?> toastIfc = hostCl.loadClass("net.minecraft.client.toast.Toast");
		Class<?> tmClass = hostCl.loadClass("net.minecraft.client.toast.ToastManager");
		Class<?> drawCtxClass = hostCl.loadClass("net.minecraft.client.gui.DrawContext");
		Class<?> trClass = hostCl.loadClass("net.minecraft.client.font.TextRenderer");

		toastIfc.getMethod("update", tmClass, long.class).invoke(toast, tm, 0L);
		assertEquals("SHOW", toastIfc.getMethod("getVisibility").invoke(toast).toString());

		Object drawCtx = drawCtxClass.getConstructor().newInstance();
		Object tr = trClass.getConstructor().newInstance();
		toastIfc.getMethod("draw", drawCtxClass, trClass, long.class).invoke(toast, drawCtx, tr, 0L);
		@SuppressWarnings("unchecked")
		List<String> ops = (List<String>) drawCtxClass.getField("ops").get(drawCtx);
		assertTrue(ops.contains("[HotSwap] 12 ms"));

		// Multiplier is 0.5 -> 2000ms display duration
		clock.set(12_000L);
		toastIfc.getMethod("update", tmClass, long.class).invoke(toast, tm, 2000L);
		assertEquals("HIDE", toastIfc.getMethod("getVisibility").invoke(toast).toString());
	}

	@Test
	void bindsMojmapNaming() throws Exception {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.client.gui.Font",
			"package net.minecraft.client.gui;\n"
			+ "public class Font {\n"
			+ "  public int width(String s) { return s.length() * 6; }\n"
			+ "  public String plainSubstrByWidth(String s, int max) { return s; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.gui.GuiGraphics",
			"package net.minecraft.client.gui;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "public class GuiGraphics {\n"
			+ "  public final List<String> ops = new ArrayList<>();\n"
			+ "  public void fill(int x1, int y1, int x2, int y2, int color) {}\n"
			+ "  public int drawString(Font font, String text, int x, int y, int color, boolean dropShadow) {\n"
			+ "    ops.add(text);\n"
			+ "    return 0;\n"
			+ "  }\n"
			+ "}\n");
		sources.put("net.minecraft.client.gui.components.toasts.ToastComponent",
			"package net.minecraft.client.gui.components.toasts;\n"
			+ "import java.util.ArrayList;\n"
			+ "import java.util.List;\n"
			+ "public class ToastComponent {\n"
			+ "  public final List<Toast> queue = new ArrayList<>();\n"
			+ "  public void addToast(Toast toast) { queue.add(toast); }\n"
			+ "  @SuppressWarnings(\"unchecked\")\n"
			+ "  public <T extends Toast> T getToast(Class<? extends T> type, Object token) {\n"
			+ "    for (Toast t : queue) if (t.getToken().equals(token)) return (T) t;\n"
			+ "    return null;\n"
			+ "  }\n"
			+ "  public double getNotificationDisplayTimeMultiplier() { return 1.0d; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.gui.components.toasts.Toast",
			"package net.minecraft.client.gui.components.toasts;\n"
			+ "import net.minecraft.client.gui.GuiGraphics;\n"
			+ "public interface Toast {\n"
			+ "  enum Visibility { SHOW, HIDE }\n"
			+ "  Visibility render(GuiGraphics graphics, ToastComponent toastComponent, long time);\n"
			+ "  default Object getToken() { return new Object(); }\n"
			+ "  default int width() { return 160; }\n"
			+ "  default int height() { return 32; }\n"
			+ "  default int slotCount() { return 1; }\n"
			+ "}\n");
		sources.put("net.minecraft.client.Minecraft",
			"package net.minecraft.client;\n"
			+ "import net.minecraft.client.gui.Font;\n"
			+ "import net.minecraft.client.gui.components.toasts.ToastComponent;\n"
			+ "public class Minecraft {\n"
			+ "  private static final Minecraft INST = new Minecraft();\n"
			+ "  public static Minecraft getInstance() { return INST; }\n"
			+ "  public final Font font = new Font();\n"
			+ "  private final ToastComponent toasts = new ToastComponent();\n"
			+ "  public ToastComponent getToasts() { return toasts; }\n"
			+ "  public void execute(Runnable r) { r.run(); }\n"
			+ "}\n");

		ClassLoader hostCl = compileIsolated(sources);
		Class<?> mcClass = hostCl.loadClass("net.minecraft.client.Minecraft");
		Object mc = mcClass.getMethod("getInstance").invoke(null);
		Object toasts = mcClass.getMethod("getToasts").invoke(mc);

		McToastPresenter presenter = new McToastPresenter(
			name -> name.equals("net.minecraft.client.Minecraft") ? mcClass : null,
			() -> 500L, s -> {}, s -> {});
		presenter.show(ToastModel.from(new HotswapBatchReport(31L,
			Collections.singletonList("net.mod.ExampleBlock"), null, null)));

		@SuppressWarnings("unchecked")
		List<Object> queue = (List<Object>) toasts.getClass().getField("queue").get(toasts);
		assertEquals(1, queue.size());
		Object toast = queue.get(0);

		Class<?> toastIfc = hostCl.loadClass("net.minecraft.client.gui.components.toasts.Toast");
		Class<?> tcClass = hostCl.loadClass("net.minecraft.client.gui.components.toasts.ToastComponent");
		Class<?> ggClass = hostCl.loadClass("net.minecraft.client.gui.GuiGraphics");
		Object gg = ggClass.getConstructor().newInstance();
		Object vis = toastIfc.getMethod("render", ggClass, tcClass, long.class)
			.invoke(toast, gg, toasts, 0L);
		assertEquals("SHOW", vis.toString());

		@SuppressWarnings("unchecked")
		List<String> ops = (List<String>) ggClass.getField("ops").get(gg);
		assertTrue(ops.contains("[HotSwap] 31 ms"));
		assertTrue(ops.contains("1 class reloaded: ExampleBlock"));
	}

	@Test
	void dedicatedServerAndDisabledPropertyFailOpenWithoutException() {
		List<String> warns = new ArrayList<>();
		McToastPresenter serverPresenter = new McToastPresenter(
			name -> null, () -> 100L, s -> {}, warns::add);
		serverPresenter.show(ToastModel.from(new HotswapBatchReport(10L,
			Collections.singletonList("com.example.ServerOnly"), null, null)));
		assertTrue(warns.isEmpty(), "Missing client class (server env) must stay silent");

		String prev = System.getProperty(McToastHotswapPlugin.DISABLE_PROPERTY);
		try {
			System.setProperty(McToastHotswapPlugin.DISABLE_PROPERTY, "false");
			assertFalse(McToastHotswapPlugin.isEnabled());
			McToastHotswapPlugin plugin = new McToastHotswapPlugin(new McToastPresenter(
				name -> { throw new AssertionError("Should not probe when disabled"); },
				() -> 0L, s -> {}, warns::add));
			plugin.afterInternalBatchReport(new HotswapBatchReport(10L,
				Collections.singletonList("com.example.Foo"), null, null));
		} finally {
			if (prev == null) {
				System.clearProperty(McToastHotswapPlugin.DISABLE_PROPERTY);
			} else {
				System.setProperty(McToastHotswapPlugin.DISABLE_PROPERTY, prev);
			}
		}
	}

	private static ClassLoader compileIsolated(Map<String, String> sources) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		Map<String, byte[]> compiled = new HashMap<>();

		try (StandardJavaFileManager std = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			ForwardingJavaFileManager<StandardJavaFileManager> fm = new ForwardingJavaFileManager<>(std) {
				@Override
				public JavaFileObject getJavaFileForOutput(Location location, String className,
				                                           JavaFileObject.Kind kind, FileObject sibling) {
					return new SimpleJavaFileObject(
						URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
						@Override
						public OutputStream openOutputStream() {
							return new ByteArrayOutputStream() {
								@Override
								public void close() throws IOException {
									compiled.put(className, toByteArray());
									super.close();
								}
							};
						}
					};
				}
			};
			List<JavaFileObject> units = new ArrayList<>(sources.size());
			for (Map.Entry<String, String> entry : sources.entrySet()) {
				String fqn = entry.getKey();
				String code = entry.getValue();
				units.add(new SimpleJavaFileObject(
					URI.create("string:///" + fqn.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
					@Override
					public CharSequence getCharContent(boolean ignoreEncodingErrors) {
						return code;
					}
				});
			}
			boolean ok = Boolean.TRUE.equals(
				compiler.getTask(null, fm, diagnostics, Arrays.asList("--release", "11"), null, units).call());
			if (!ok) {
				StringBuilder err = new StringBuilder();
				for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
					err.append(d).append('\n');
				}
				throw new IllegalStateException("Mock host compilation failed:\n" + err);
			}
		}

		return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = compiled.get(name);
				if (bytes == null) {
					throw new ClassNotFoundException(name);
				}
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
	}
}

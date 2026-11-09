package remold.reflect.mixin;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抑制器 fail-closed 契约（C4）：抑制器缺失、返回 {@code null} 或抛异常都表示“本次无法抑制”，
 * 必须放弃登记/准备、输出指向性诊断，且异常不得外泄到调用方。
 *
 * <p><b>为什么需要独立 ClassLoader 替身</b>：{@code NewMixinRegistrar} 判断“目标已加载”用的是
 * 织入器所在 ClassLoader 的 Mixin 服务。真实 Mixin 服务的类跟踪器只会对已经加载的宿主类回答
 * {@code true}，测试无法让宿主真正“已加载”探针目标。这里把同形的
 * {@code org.spongepowered.asm.service.MixinService} 替身编译后放进子 ClassLoader：子加载器的
 * 跟踪器对本次探针目标回答“已加载”，从而把执行路径真正推进到抑制窗口，而不是绕过它。</p>
 *
 * <p><b>判据落在业务终局</b>：登记的终局（是否留下 {@code MixinInfo}/{@code mixinClasses} 条目、
 * 是否执行 {@code prepare()}）+ 抑制函数是否真的被调用（前置基线，防止“根本没走到抑制”也算绿）+
 * 诊断文案是否指向目标/检查点/动作。</p>
 */
class NewMixinRegistrarFailClosedTest {

	private static final String MIXIN_PACKAGE = "com.example.failclosed.mixin.";
	private static final String MIXIN_CLASS = MIXIN_PACKAGE + "ProbeNewMixin";
	private static final String SIMPLE_NAME = "ProbeNewMixin";
	private static final String TARGET_INTERNAL = "com/example/target/AlreadyLoadedTarget";
	private static final String TARGET_DOTTED = "com.example.target.AlreadyLoadedTarget";
	private static final String HOOK = "test loader class tracker";

	private static final class StubHolder {
		private static final Stub INSTANCE = Stub.create();
	}

	@Test
	void nullTokenAbandonsRegistrationAndEmitsADirectedDiagnostic() throws Exception {
		Harness harness = new Harness();
		AtomicInteger suppressCalls = new AtomicInteger();
		AtomicBoolean restored = new AtomicBoolean();

		Captured captured = capture(() -> NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			targets -> {
				suppressCalls.incrementAndGet();
				return null;
			},
			token -> restored.set(true),
			HOOK));

		assertEquals(1, suppressCalls.get(),
			"baseline: the already-loaded target must really reach the suppressor, otherwise this test proves nothing");
		assertTrue(captured.result.isEmpty(), "fail-closed: no target may be reported as registered");
		assertFalse(harness.config.mixinClasses.contains(SIMPLE_NAME), "the simple name must be rolled back");
		assertNull(harness.config.find(MIXIN_CLASS), "no MixinInfo may survive a refused registration");
		assertEquals(1, harness.config.mixins.size(), "the pre-existing mixin must stay untouched");
		assertTrue(harness.config.unhandledTargets.isEmpty(), "a refused registration must not leave unhandled targets");
		assertEquals(0, harness.config.prepareCalls,
			"prepare() must not run when the target check could not be suppressed");
		assertFalse(restored.get(), "a null token must never be handed to restore");
		assertDirectedDiagnostic(captured.output, "the suppression hook returned no token");
	}

	@Test
	void throwingSuppressorIsSwallowedAndReported() throws Exception {
		Harness harness = new Harness();
		AtomicInteger suppressCalls = new AtomicInteger();

		Captured captured = capture(() -> NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			targets -> {
				suppressCalls.incrementAndGet();
				throw new IllegalStateException("tracker exploded");
			},
			token -> {
			},
			HOOK));

		assertEquals(1, suppressCalls.get(), "baseline: the suppressor must have been invoked");
		assertTrue(captured.result.isEmpty(), "fail-closed: a throwing suppressor must not register anything");
		assertFalse(harness.config.mixinClasses.contains(SIMPLE_NAME));
		assertNull(harness.config.find(MIXIN_CLASS));
		assertEquals(0, harness.config.prepareCalls);
		assertTrue(captured.output.contains("tracker exploded"),
			"the diagnostic must carry the underlying cause:\n" + captured.output);
		assertDirectedDiagnostic(captured.output, "suppressing it failed");
	}

	@Test
	void missingSuppressorHookFailsClosedWithoutTouchingTheConfig() throws Exception {
		Harness harness = new Harness();

		Captured captured = capture(() -> NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			null, token -> {
			}, HOOK));

		assertTrue(captured.result.isEmpty());
		assertFalse(harness.config.mixinClasses.contains(SIMPLE_NAME));
		assertNull(harness.config.find(MIXIN_CLASS));
		assertEquals(0, harness.config.prepareCalls);
		assertDirectedDiagnostic(captured.output, "no usable suppression hook");
	}

	@Test
	void halfWiredSuppressorIsNeverApplied() throws Exception {
		Harness harness = new Harness();
		AtomicInteger suppressCalls = new AtomicInteger();

		Captured captured = capture(() -> NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			targets -> {
				suppressCalls.incrementAndGet();
				return new Object();
			},
			null,
			HOOK));

		assertEquals(0, suppressCalls.get(),
			"a suppression without a restore would leave the loader's tracker permanently wrong; it must not start");
		assertTrue(captured.result.isEmpty());
		assertNull(harness.config.find(MIXIN_CLASS));
		assertEquals(0, harness.config.prepareCalls);
		assertDirectedDiagnostic(captured.output, "no usable suppression hook");
	}

	@Test
	void aSuppressedRegistrationSucceedsAndRestoresTheTracker() throws Exception {
		Harness harness = new Harness();
		Object token = new Object();
		AtomicReference<Object> restoredWith = new AtomicReference<>();
		AtomicInteger suppressCalls = new AtomicInteger();

		List<String> targets = NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			loaded -> {
				suppressCalls.incrementAndGet();
				return token;
			},
			restoredWith::set,
			HOOK);

		assertEquals(1, suppressCalls.get());
		assertEquals(List.of(TARGET_INTERNAL), targets);
		assertEquals(1, harness.config.prepareCalls, "prepare() must run once the tracker check is suppressed");
		assertTrue(harness.config.mixinClasses.contains(SIMPLE_NAME));
		assertNotNull(harness.config.find(MIXIN_CLASS));
		assertSame(token, restoredWith.get(), "the same token must come back through the restore function");
	}

	@Test
	void restoreFailureDoesNotDiscardAnAcceptedRegistration() throws Exception {
		Harness harness = new Harness();

		Captured captured = capture(() -> NewMixinRegistrar.tryRegister(harness.transformer, MIXIN_CLASS, mixinNode(),
			loaded -> new Object(),
			token -> {
				throw new IllegalStateException("tracker restore exploded");
			},
			HOOK));

		assertEquals(List.of(TARGET_INTERNAL), captured.result,
			"the mixin is already registered inside Mixin; a restore failure must not pretend it was not");
		assertNotNull(harness.config.find(MIXIN_CLASS));
		assertTrue(captured.output.contains("Failed to restore"), captured.output);
	}

	private static void assertDirectedDiagnostic(String output, String expectedReason) {
		assertTrue(output.contains("[WARN]"), "the refusal must be a warn, not a silent skip:\n" + output);
		assertTrue(output.contains(MIXIN_CLASS), "the diagnostic must name the refused mixin:\n" + output);
		assertTrue(output.contains(TARGET_DOTTED), "the diagnostic must name the already-loaded target:\n" + output);
		assertTrue(output.contains(HOOK), "the diagnostic must name the loader hook that could not be suppressed:\n" + output);
		assertTrue(output.contains(expectedReason), "the diagnostic must name the reason:\n" + output);
		assertTrue(output.toLowerCase(Locale.ROOT).contains("not registered"),
			"the diagnostic must not pretend success:\n" + output);
		assertTrue(output.toLowerCase(Locale.ROOT).contains("restart"),
			"the diagnostic must state the actionable next step:\n" + output);
	}

	private static ClassNode mixinNode() {
		ClassNode node = new ClassNode();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		mixin.values.add("value");
		mixin.values.add(Type.getObjectType(TARGET_INTERNAL));
		node.invisibleAnnotations = new ArrayList<>();
		node.invisibleAnnotations.add(mixin);
		return node;
	}

	// ------------------------------------------------------------------ stdout capture

	private interface Registration {
		List<String> run() throws Exception;
	}

	private record Captured(List<String> result, String output) {
	}

	private static Captured capture(Registration registration) throws Exception {
		PrintStream original = System.out;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		List<String> result;
		PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(capture);
		try {
			result = registration.run();
		} finally {
			System.setOut(original);
			capture.close();
		}
		return new Captured(result, buffer.toString(StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------ child-loader stub

	private static final class Stub {
		private final Class<?> transformerType;

		private Stub(Class<?> transformerType) {
			this.transformerType = transformerType;
		}

		static Stub create() {
			try {
				Map<String, byte[]> compiled = compile("org.spongepowered.asm.service.MixinService", SOURCE);
				StubLoader loader = new StubLoader(NewMixinRegistrarFailClosedTest.class.getClassLoader(), compiled);
				return new Stub(loader.loadClass("org.spongepowered.asm.service.MixinService$Transformer"));
			} catch (Exception e) {
				throw new IllegalStateException("unable to build the child-loader Mixin service stub", e);
			}
		}

		Object newTransformer() throws Exception {
			return this.transformerType.getConstructor().newInstance();
		}
	}

	/**
	 * 同形的 Mixin 服务替身：{@code getService().getClassTracker().isClassLoaded(target)} 对本次探针
	 * 目标回答 {@code true}（= 目标已加载），其它答案 {@code false}。{@code Transformer} 只提供一个
	 * public {@code processor} 字段，它的 Realm 就是本替身的 ClassLoader。
	 */
	private static final String SOURCE = """
		package org.spongepowered.asm.service;

		public final class MixinService {

			private static final Service SERVICE = new Service();

			public static Object getService() {
				return SERVICE;
			}

			public static final class Service {
				private final Tracker tracker = new Tracker();

				public Object getClassTracker() {
					return tracker;
				}
			}

			public static final class Tracker {
				public boolean isClassLoaded(String name) {
					return "TARGET".equals(name);
				}
			}

			public static final class Transformer {
				public Object processor;

				public Transformer() {
				}
			}
		}
		""".replace("TARGET", TARGET_DOTTED);

	private static Map<String, byte[]> compile(String fqcn, String source) throws Exception {
		Path root = Files.createTempDirectory("remold-mixin-suppressor-");
		Path sourceFile = root.resolve(fqcn.replace('.', '/') + ".java");
		Files.createDirectories(sourceFile.getParent());
		Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
		Path classes = Files.createDirectories(root.resolve("classes"));

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "the test JVM must ship a Java compiler to build the loader fixture");
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		int exit = compiler.run(null, diagnostics, diagnostics,
			"-proc:none", "-nowarn", "-encoding", "UTF-8", "-d", classes.toString(), sourceFile.toString());
		assertEquals(0, exit, "stub compilation failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));

		Map<String, byte[]> out = new HashMap<>();
		try (Stream<Path> walk = Files.walk(classes)) {
			for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
				if (!file.getFileName().toString().endsWith(".class")) {
					continue;
				}
				String relative = classes.relativize(file).toString().replace('\\', '/');
				String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
				out.put(name, Files.readAllBytes(file));
			}
		}
		return out;
	}

	/** 只对本替身登记的类名 child-first，其余全部委托父加载器。 */
	private static final class StubLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		StubLoader(ClassLoader parent, Map<String, byte[]> classes) {
			super(parent);
			this.classes = classes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					byte[] bytes = this.classes.get(name);
					loaded = bytes == null
						? super.loadClass(name, false)
						: defineClass(name, bytes, 0, bytes.length);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}

	// ------------------------------------------------------------------ fakes

	private static final class Harness {
		private final Object transformer;
		private final FakeConfig config = new FakeConfig();

		Harness() throws Exception {
			this.transformer = StubHolder.INSTANCE.newTransformer();
			this.transformer.getClass().getField("processor").set(this.transformer, new FakeProcessor(this.config));
			this.config.addExistingMixin("ExistingMixin");
		}
	}

	private static final class FakeProcessor {
		@SuppressWarnings("unused")
		private final List<FakeConfig> configs;
		@SuppressWarnings("unused")
		private final Object extensions = new Object();

		FakeProcessor(FakeConfig config) {
			this.configs = new ArrayList<>(List.of(config));
		}
	}

	private static final class FakeConfig {
		private final List<String> mixinClasses = new ArrayList<>();
		private final List<FakeInfo> mixins = new ArrayList<>();
		private final Map<String, List<FakeInfo>> mixinMapping = new LinkedHashMap<>();
		private final Set<String> unhandledTargets = new LinkedHashSet<>();
		private final Set<String> globalMixinList = new LinkedHashSet<>();
		private boolean prepared;
		private int prepareCalls;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "failclosed_probe.mixins.json";
		}

		void addExistingMixin(String simpleName) {
			String className = MIXIN_PACKAGE + simpleName;
			this.mixinClasses.add(simpleName);
			FakeInfo info = new FakeInfo(className);
			this.mixins.add(info);
			this.globalMixinList.add(className);
		}

		FakeInfo find(String className) {
			for (FakeInfo info : this.mixins) {
				if (info.getClassName().equals(className)) {
					return info;
				}
			}
			return null;
		}

		@SuppressWarnings("unused")
		private void prepare(Object extensions) {
			this.prepareCalls++;
			for (String simpleName : this.mixinClasses) {
				String className = MIXIN_PACKAGE + simpleName;
				if (find(className) != null) {
					continue;
				}
				FakeInfo info = new FakeInfo(className);
				this.mixins.add(info);
				this.mixinMapping.computeIfAbsent(TARGET_DOTTED, key -> new ArrayList<>()).add(info);
				this.unhandledTargets.add(TARGET_DOTTED);
				this.globalMixinList.add(className);
			}
			this.prepared = true;
		}
	}

	private static final class FakeInfo {
		private final String className;

		FakeInfo(String className) {
			this.className = className;
		}

		String getClassName() {
			return className;
		}

		List<String> getTargetClasses() {
			return List.of(TARGET_INTERNAL);
		}

		@SuppressWarnings("unused")
		private void validate() {
		}
	}
}

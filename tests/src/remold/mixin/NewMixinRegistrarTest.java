package remold.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NewMixinRegistrar} 的登记/回滚契约测试。
 *
 * <p>真实 Mixin 的 {@code MixinConfig} 是包私有类，无法在普通 JUnit 里直接构造；这里用
 * 字段/方法名与 Mixin 0.8.5 同构的 fake 对象验证反射层：包匹配、只登记一次、登记后
 * {@code MixinInfo.validate()} 被调用、校验失败时从配置/映射/全局集合完整回滚。
 * 真实 Mixin 版本上的端到端行为仍由 Fabric 客户端/服务端探针覆盖，不能仅凭本测试宣称
 * 运行时已验证。</p>
 */
class NewMixinRegistrarTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";

	@Test
	void registersNewMixinIntoTHeOwningConfig() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		config.addExistingMixin("ExistingMixin");
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.CreepMixin", mixinNode());

		assertEquals(List.of("net/minecraft/entity/mob/CreeperEntity"), targets);
		assertTrue(config.mixinClasses.contains("CreepMixin"), "simple name must be added to mixinClasses");
		assertEquals(2, config.mixins.size(), "existing + new mixin");
		FakeMixinInfo added = config.find("com.example.mixin.CreepMixin");
		assertNotNull(added);
		assertTrue(added.validated, "MixinInfo.validate() must run after prepare()");
		assertTrue(added.targets.equals(List.of("net/minecraft/entity/mob/CreeperEntity")));
		assertTrue(config.prepared, "prepare() must leave the config prepared");
		assertTrue(FakeConfig.globalMixinList.contains("com.example.mixin.CreepMixin"));
	}

	@Test
	void secondCallForTheSameMixinReturnsEmptyWithoutDuplicating() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> first = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.CreepMixin", mixinNode());
		List<String> second = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.CreepMixin", mixinNode());

		assertFalse(first.isEmpty());
		assertTrue(second.isEmpty());
		assertEquals(1, config.mixins.size());
	}

	@Test
	void validationFailureRollsBackEveryRegistryEntry() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		config.failValidation = true;
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.CreepMixin", mixinNode());

		assertTrue(targets.isEmpty(), "failed validation must not return targets");
		assertFalse(config.mixinClasses.contains("CreepMixin"));
		assertTrue(config.mixins.isEmpty());
		assertTrue(config.mixinMapping.isEmpty());
		assertTrue(config.unhandledTargets.isEmpty());
		assertFalse(FakeConfig.globalMixinList.contains("com.example.mixin.CreepMixin"));
	}

	@Test
	void nonMatchingPackageIsNotTouched() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.other.mixin.OtherMixin", new ClassNode());

		assertTrue(targets.isEmpty());
		assertTrue(config.mixinClasses.isEmpty());
		assertTrue(config.mixins.isEmpty());
	}

	/**
	 * 子包里的 Mixin 必须能登记：{@code MixinConfig.mixinClasses} 存的是<b>相对名</b>，
	 * 允许多层子包（真实工程里 {@code honkai.ProbeSubHotcMixin} / {@code client.hud.YyyMixin}）。
	 * 旧实现按"单段简单名"拒绝点号，会把子包里的合法 Mixin 全部挡在登记之外。
	 */
	@Test
	void registersMixinDeclaredInASubPackage() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.honkai.ProbeSubHotcMixin", mixinNode());

		assertEquals(List.of("net/minecraft/entity/mob/CreeperEntity"), targets,
			"a sub-package mixin must be registered like any other");
		assertTrue(config.mixinClasses.contains("honkai.ProbeSubHotcMixin"),
			"mixinClasses must receive the dotted relative name: " + config.mixinClasses);
		assertNotNull(config.find("com.example.mixin.honkai.ProbeSubHotcMixin"),
			"the MixinInfo must be keyed by the full dotted class name");
		assertTrue(FakeConfig.globalMixinList.contains("com.example.mixin.honkai.ProbeSubHotcMixin"),
			"globalMixinList must carry the full dotted class name");
	}

	/** 对照：真正非法的相对名（空段）仍然必须被拒绝，不得因为放宽点号而一并放行。 */
	@Test
	void malformedRelativeNameIsRejectedInsteadOfRegistered() {
		FakeTransformer transformer = new FakeTransformer();
		FakeConfig config = new FakeConfig();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin..Bad", mixinNode());

		assertTrue(targets.isEmpty(), "an empty path segment is not a valid mixin name");
		assertTrue(config.mixinClasses.isEmpty(), "a malformed name must not reach mixinClasses");
		assertTrue(config.mixins.isEmpty());
		assertFalse(remold.reflect.mixin.NewMixinRegistrar.isValidRelativeMixinName(""), "empty name is invalid");
		assertFalse(remold.reflect.mixin.NewMixinRegistrar.isValidRelativeMixinName(".leading"), "leading dot is invalid");
		assertFalse(remold.reflect.mixin.NewMixinRegistrar.isValidRelativeMixinName("trailing."), "trailing dot is invalid");
		assertTrue(remold.reflect.mixin.NewMixinRegistrar.isValidRelativeMixinName("client.hud.YyyMixin"), "nested relative name");
	}

	@Test
	void mixinInternalsStillMatchTheReflectionContract() throws Exception {
		Class<?> config = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig", false,
			NewMixinRegistrarTest.class.getClassLoader());
		for (String field : List.of("mixinClasses", "mixins", "mixinMapping", "unhandledTargets",
			"prepared", "globalMixinList")) {
			assertNotNull(config.getDeclaredField(field), "MixinConfig." + field + " is required by NewMixinRegistrar");
		}
		boolean hasPrepare = false;
		for (java.lang.reflect.Method method : config.getDeclaredMethods()) {
			if (method.getName().equals("prepare") && method.getParameterCount() == 1) {
				hasPrepare = true;
			}
		}
		assertTrue(hasPrepare, "MixinConfig.prepare(Extensions) is required by NewMixinRegistrar");
		Class<?> info = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo", false,
			NewMixinRegistrarTest.class.getClassLoader());
		assertNotNull(info.getDeclaredMethod("validate"));
		assertNotNull(info.getDeclaredMethod("getTargetClasses"));
		assertNotNull(info.getDeclaredMethod("getClassName"));
		Class<?> processor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinProcessor", false,
			NewMixinRegistrarTest.class.getClassLoader());
		assertNotNull(processor.getDeclaredField("configs"));
		assertNotNull(processor.getDeclaredField("extensions"));
	}

	@Test
	void missingProcessorDegradesToEmptyWithoutThrowing() {
		FakeTransformer transformer = new FakeTransformer();
		transformer.processor = null;

		List<String> targets = NewMixinRegistrar.tryRegister(transformer,
			"com.example.mixin.CreepMixin", mixinNode());

		assertTrue(targets.isEmpty());
	}

	private static ClassNode mixinNode() {
		ClassNode node = new ClassNode();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		mixin.values.add("value");
		mixin.values.add(Type.getObjectType("com/example/probe/ProbeTarget"));
		node.invisibleAnnotations = new ArrayList<>();
		node.invisibleAnnotations.add(mixin);
		return node;
	}

	// ------------------------------------------------------------------ fakes

	private static final class FakeTransformer implements IMixinTransformer {
		Object processor;

		@Override
		public void audit(MixinEnvironment environment) {
		}

		@Override
		public List<String> reload(String name, ClassNode classNode) {
			return Collections.emptyList();
		}

		@Override
		public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) {
			return false;
		}

		@Override
		public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
			return basicClass;
		}

		@Override
		public byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes) {
			return classBytes;
		}

		@Override
		public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) {
			return false;
		}

		@Override
		public byte[] generateClass(MixinEnvironment environment, String name) {
			return null;
		}

		@Override
		public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) {
			return false;
		}

		@Override
		public IExtensionRegistry getExtensions() {
			return null;
		}
	}

	private static final class FakeProcessor {
		@SuppressWarnings("unused")
		private final List<FakeConfig> configs;
		@SuppressWarnings("unused")
		private final Object extensions = new Object();

		FakeProcessor(List<FakeConfig> configs) {
			this.configs = configs;
		}
	}

	static final class FakeConfig {
		@SuppressWarnings("unused")
		static final Set<String> globalMixinList = new HashSet<>();

		private final List<String> mixinClasses = new ArrayList<>();
		private final List<FakeMixinInfo> mixins = new ArrayList<>();
		private final Map<String, List<FakeMixinInfo>> mixinMapping = new LinkedHashMap<>();
		private final Set<String> unhandledTargets = new HashSet<>();
		private boolean prepared;
		private boolean failValidation;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "example_mod.mixins.json";
		}

		@SuppressWarnings("unused")
		private void prepare(Object extensions) {
			for (String simpleName : mixinClasses) {
				String className = MIXIN_PACKAGE + simpleName;
				if (find(className) != null) {
					continue;
				}
				FakeMixinInfo info = new FakeMixinInfo(className, failValidation);
				mixins.add(info);
				mixinMapping.computeIfAbsent("net.minecraft.entity.mob.CreeperEntity",
					key -> new ArrayList<>()).add(info);
				unhandledTargets.add("net.minecraft.entity.mob.CreeperEntity");
				globalMixinList.add(className);
			}
			prepared = true;
		}

		void addExistingMixin(String simpleName) {
			String className = MIXIN_PACKAGE + simpleName;
			mixinClasses.add(simpleName);
			FakeMixinInfo info = new FakeMixinInfo(className, false);
			mixins.add(info);
			globalMixinList.add(className);
			prepared = true;
		}

		FakeMixinInfo find(String className) {
			for (FakeMixinInfo info : mixins) {
				if (info.getClassName().equals(className)) {
					return info;
				}
			}
			return null;
		}
	}

	static final class FakeMixinInfo {
		private final String className;
		private final List<String> targets = new ArrayList<>(List.of("net/minecraft/entity/mob/CreeperEntity"));
		private final boolean failValidation;
		private boolean validated;

		FakeMixinInfo(String className, boolean failValidation) {
			this.className = className;
			this.failValidation = failValidation;
		}

		String getClassName() {
			return className;
		}

		List<String> getTargetClasses() {
			return targets;
		}

		@SuppressWarnings("unused")
		private void validate() {
			if (failValidation) {
				throw new IllegalStateException("simulated conform failure");
			}
			validated = true;
		}
	}
}

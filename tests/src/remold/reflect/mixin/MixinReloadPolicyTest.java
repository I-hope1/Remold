package remold.reflect.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MixinReloadPolicy} 的契约测试：priority 字段对齐、targets 差集识别、interfaces 预对齐与回滚。
 *
 * <p>真实 Mixin 的 {@code MixinInfo} 是包私有类，无法在普通 JUnit 里构造；这里用字段/方法名与
 * Mixin 0.8.5 同构的 fake 对象验证反射层。真实 Mixin 版本上的端到端行为由 Fabric/Forge 探针覆盖，
 * 不能仅凭本测试宣称运行时已验证。</p>
 */
class MixinReloadPolicyTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";
	private static final String MIXIN = MIXIN_PACKAGE + "ProbeMixin";

	@Test
	void priorityChangeAlignsTheFinalFieldBeforeReload() {
		Fixture fixture = new Fixture(0, List.of("com/example/Target"), Set.of());
		ClassNode node = mixinNode(120, "com/example/Target", List.of());

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, false);

		assertTrue(plan.usable(), "plan must be usable: " + plan.failure());
		assertTrue(plan.priorityChanged());
		assertTrue(plan.priorityPreAligned(), "the final priority field must be written before reload");
		assertEquals(0, plan.oldPriority());
		assertEquals(120, plan.newPriority());
		assertEquals(120, fixture.info.getPriority(),
			"MixinInfo.priority must read the new value (otherwise Reloaded rejects the reload)");
		assertFalse(plan.targetsChanged());
		assertFalse(plan.interfacesChanged());
	}

	@Test
	void missingPriorityAnnotationFallsBackToTheConfigDefault() {
		// 启动时 priority 已经由同一默认值写入；这里断言“注解未写”不会造成一次假的优先级变更。
		Fixture fixture = new Fixture(1000, List.of("com/example/Target"), Set.of());
		ClassNode node = mixinNode(null, "com/example/Target", List.of());

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, false);

		assertTrue(plan.usable(), plan.failure());
		assertFalse(plan.priorityChanged(), "no annotation priority means the config default applies (equal here)");
		assertEquals(1000, fixture.config.defaultMixinPriority);
	}

	@Test
	void targetChangeIsReportedAsUnchangedMappingButNewTargetList() {
		Fixture fixture = new Fixture(0, List.of("com/example/OldTarget"), Set.of());
		ClassNode node = mixinNode(0, "com/example/NewTarget", List.of());

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, false);

		assertTrue(plan.usable(), plan.failure());
		assertTrue(plan.targetsChanged());
		assertEquals(List.of("com.example.OldTarget"), plan.oldTargets());
		assertEquals(List.of("com.example.NewTarget"), plan.newTargets());
		assertEquals(0, fixture.info.getPriority(), "priority must not be touched when unchanged");
	}

	@Test
	void interfaceChangeIsRejectedWithoutEnhancedRedefinition() {
		Fixture fixture = new Fixture(0, List.of("com/example/Target"), Set.of());
		ClassNode node = mixinNode(0, "com/example/Target", List.of("com/example/DuckAccess"));

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, false);

		assertTrue(plan.usable(), plan.failure());
		assertTrue(plan.interfacesChanged());
		assertFalse(plan.interfacePreAligned(), "standard HotSpot must not align the interface tables");
		assertFalse(fixture.state.interfaces.contains("com/example/DuckAccess"),
			"the old state must stay untouched so the native reload still rejects the change");
		assertFalse(fixture.classInfo.interfaces.contains("com/example/DuckAccess"));
	}

	@Test
	void interfaceAdditionIsAlignedOnAnEnhancedHost() {
		Fixture fixture = new Fixture(0, List.of("com/example/Target"), Set.of("com/example/OldDuck"));
		ClassNode node = mixinNode(0, "com/example/Target", List.of("com/example/NewDuck"));

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, true);

		assertTrue(plan.usable(), plan.failure());
		assertTrue(plan.interfacesChanged());
		assertTrue(plan.interfacePreAligned());
		assertEquals(Set.of("com/example/NewDuck"), fixture.state.interfaces,
			"the Reloaded comparison baseline must equal the new interface set");
		assertEquals(Set.of("com/example/NewDuck"), fixture.classInfo.interfaces,
			"the reused ClassInfo interface table must follow the new set");
		assertTrue(plan.addedInterfaces().contains("com/example/NewDuck"));
		assertTrue(plan.removedInterfaces().contains("com/example/OldDuck"));
	}

	@Test
	void rollbackRestoresPriorityAndInterfacesAfterARejectedReload() {
		Fixture fixture = new Fixture(7, List.of("com/example/Target"), Set.of("com/example/OldDuck"));
		ClassNode node = mixinNode(99, "com/example/Target", List.of("com/example/NewDuck"));

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN, node, true);
		assertTrue(plan.usable(), plan.failure());
		assertEquals(99, fixture.info.getPriority());
		assertEquals(Set.of("com/example/NewDuck"), fixture.state.interfaces);

		MixinReloadPolicy.rollback(fixture.transformer, MIXIN, plan);

		assertEquals(7, fixture.info.getPriority(), "priority must be rolled back to the previous value");
		assertEquals(Set.of("com/example/OldDuck"), fixture.state.interfaces,
			"state interfaces must be rolled back so the next reload compares against the old baseline");
		assertEquals(Set.of("com/example/OldDuck"), fixture.classInfo.interfaces);
	}

	@Test
	void unregisteredMixinYieldsAnUnusablePlan() {
		Fixture fixture = new Fixture(0, List.of("com/example/Target"), Set.of());
		fixture.config.mixins.clear();

		MixinReloadPolicy.Plan plan = MixinReloadPolicy.prepare(fixture.transformer, MIXIN,
			mixinNode(0, "com/example/Target", List.of()), false);

		assertFalse(plan.usable(), "an unregistered mixin must fall back to the native reload path");
		assertNotNull(plan.failure());
	}

	/**
	 * The reflection contract against the real Mixin jar on the test classpath: if upstream renames or
	 * re-types any of these members, the policy silently degrades to "restart required". This test makes
	 * that degradation visible instead.
	 */
	@Test
	void mixinInternalsStillMatchTheReflectionContract() throws Exception {
		ClassLoader loader = MixinReloadPolicyTest.class.getClassLoader();
		Class<?> info = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo", false, loader);
		java.lang.reflect.Field priority = info.getDeclaredField("priority");
		assertTrue(java.lang.reflect.Modifier.isFinal(priority.getModifiers()),
			"MixinInfo.priority is expected to be final; the Unsafe path assumes it");
		assertEquals(int.class, priority.getType());
		assertNotNull(info.getDeclaredField("state"), "MixinInfo.state is required for interface alignment");
		assertNotNull(info.getDeclaredField("info"), "MixinInfo.info (ClassInfo) is required for interface alignment");
		assertNotNull(info.getDeclaredMethod("getPriority"));
		Class<?> state = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo$State", false, loader);
		assertNotNull(state.getDeclaredField("interfaces"));
		Class<?> classInfo = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", false, loader);
		assertNotNull(classInfo.getDeclaredField("interfaces"));
		boolean hasAddInterface = false;
		for (java.lang.reflect.Method method : classInfo.getDeclaredMethods()) {
			if (method.getName().equals("addInterface") && method.getParameterCount() == 1) {
				hasAddInterface = true;
			}
		}
		assertTrue(hasAddInterface, "ClassInfo.addInterface(String) is required for interface alignment");
	}

	// ------------------------------------------------------------------ fakes

	private static ClassNode mixinNode(Integer priority, String target, List<String> interfaces) {
		ClassNode node = new ClassNode();
		node.interfaces = new ArrayList<>(interfaces);
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		if (target != null) {
			mixin.values.add("value");
			mixin.values.add(Type.getObjectType(target));
		}
		if (priority != null) {
			mixin.values.add("priority");
			mixin.values.add(priority);
		}
		node.invisibleAnnotations = new ArrayList<>();
		node.invisibleAnnotations.add(mixin);
		return node;
	}

	private static final class Fixture {
		final FakeTransformer transformer = new FakeTransformer();
		final FakeConfig config = new FakeConfig();
		final FakeState state = new FakeState();
		final FakeClassInfo classInfo = new FakeClassInfo();
		final FakeInfo info;

		Fixture(int priority, List<String> targets, Set<String> interfaces) {
			this.info = new FakeInfo(priority, targets, this.state, this.classInfo);
			this.state.interfaces.addAll(interfaces);
			this.classInfo.interfaces.addAll(interfaces);
			this.config.mixins.add(this.info);
			this.config.mixinClasses.add("ProbeMixin");
			for (String target : targets) {
				this.config.mixinMapping.computeIfAbsent(target.replace('/', '.'),
					key -> new ArrayList<>()).add(this.info);
				this.config.unhandledTargets.add(target.replace('/', '.'));
			}
			this.config.globalMixinList.add(MIXIN);
			this.transformer.processor = new FakeProcessor(Collections.singletonList(this.config));
		}
	}

	private static final class FakeTransformer {
		Object processor;
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

	private static final class FakeConfig {
		final List<String> mixinClasses = new ArrayList<>();
		final List<FakeInfo> mixins = new ArrayList<>();
		final Map<String, List<FakeInfo>> mixinMapping = new LinkedHashMap<>();
		final Set<String> unhandledTargets = new LinkedHashSet<>();
		final Set<String> globalMixinList = new HashSet<>();
		final int defaultMixinPriority = 1000;

		@SuppressWarnings("unused")
		private boolean prepared;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "example_mod.mixins.json";
		}

		int getDefaultMixinPriority() {
			return this.defaultMixinPriority;
		}
	}

	private static final class FakeState {
		final Set<String> interfaces = new LinkedHashSet<>();
	}

	private static final class FakeClassInfo {
		final Set<String> interfaces = new LinkedHashSet<>();

		@SuppressWarnings("unused")
		private void addInterface(String internalName) {
			this.interfaces.add(internalName);
		}
	}

	private static final class FakeInfo {
		private int priority;
		private final List<String> targets;
		private final FakeState state;
		private final FakeClassInfo info;

		FakeInfo(int priority, List<String> targets, FakeState state, FakeClassInfo info) {
			this.priority = priority;
			this.targets = new ArrayList<>(targets);
			this.state = state;
			this.info = info;
		}

		int getPriority() {
			return this.priority;
		}

		String getClassName() {
			return MIXIN;
		}

		List<String> getTargetClasses() {
			return new ArrayList<>(this.targets);
		}

		@SuppressWarnings("unused")
		private void validate() {
		}
	}
}

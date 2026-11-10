package remold.reflect.mixin;

import java.util.ArrayList;
import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MixinRetarget} 的契约测试：差集卸载 + 重新挂载，以及登记失败后的完整回滚。
 *
 * <p>用与 Mixin 0.8.5 同构的 fake 配置验证反射层；真实 Mixin 上的端到端行为由 Fabric/Forge 探针覆盖。</p>
 */
class MixinRetargetTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";
	private static final String MIXIN = MIXIN_PACKAGE + "ProbeMixin";
	private static final String OLD_TARGET = "com/example/OldTarget";
	private static final String NEW_TARGET = "com/example/NewTarget";

	@Test
	void retargetMovesTheMixinFromOldTargetToNewTarget() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		fixture.config.nextTargets = List.of(NEW_TARGET);
		ClassNode node = mixinNode(NEW_TARGET);

		List<String> affected = MixinRetarget.apply(fixture.transformer, MIXIN, node, null, null, null);

		assertNotNull(affected, "retarget must succeed for a well-formed config");
		assertEquals(Set.of("com.example.OldTarget", "com.example.NewTarget"), new HashSet<>(affected),
			"both the removed and the added target must be re-woven");
		assertEquals(1, fixture.config.mixins.size(), "exactly one MixinInfo must remain registered");
		FakeInfo info = fixture.config.mixins.get(0);
		assertEquals(List.of(NEW_TARGET), info.targets, "the new MixinInfo must carry the new target set");
		assertFalse(fixture.config.mixinMapping.containsKey("com.example.OldTarget"),
			"the old target mapping must be gone so a re-weave drops the mixin injections");
		assertTrue(fixture.config.mixinMapping.containsKey("com.example.NewTarget"));
		assertTrue(fixture.config.mixinMapping.get("com.example.NewTarget").contains(info));
		assertTrue(fixture.config.unhandledTargets.contains("com.example.NewTarget"));
		assertFalse(fixture.config.unhandledTargets.contains("com.example.OldTarget"));
		assertTrue(fixture.config.globalMixinList.contains(MIXIN));
		assertTrue(fixture.config.mixinClasses.contains("ProbeMixin"));
		assertTrue(fixture.config.prepared, "prepare() must leave the config prepared");
	}

	@Test
	void failedRegistrationRestoresThePreviousMixinCompletely() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		fixture.config.nextTargets = List.of(NEW_TARGET);
		fixture.config.failPrepare = true;
		ClassNode node = mixinNode(NEW_TARGET);
		FakeInfo previous = fixture.config.mixins.get(0);

		List<String> affected = MixinRetarget.apply(fixture.transformer, MIXIN, node, null, null, null);

		assertNull(affected, "a failed registration must report failure so the caller falls back to reload");
		assertEquals(1, fixture.config.mixins.size());
		assertTrue(fixture.config.mixins.contains(previous),
			"the previous MixinInfo object must be put back into the config");
		assertTrue(fixture.config.mixinMapping.getOrDefault("com.example.OldTarget", List.of()).contains(previous),
			"the old target mapping must be restored");
		assertTrue(fixture.config.unhandledTargets.contains("com.example.OldTarget"));
		assertTrue(fixture.config.globalMixinList.contains(MIXIN));
		assertTrue(fixture.config.mixinClasses.contains("ProbeMixin"));
		assertTrue(fixture.config.prepared, "the config must be prepared again after the rollback");
		assertFalse(fixture.config.mixinMapping.containsKey("com.example.NewTarget"),
			"a failed registration must not leave the new target mapping behind");
	}

	@Test
	void missingMixinInfoIsNotTouched() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		fixture.config.mixins.clear();
		fixture.config.mixinClasses.clear();
		fixture.config.mixinMapping.clear();
		fixture.config.unhandledTargets.clear();
		fixture.config.globalMixinList.clear();

		List<String> affected = MixinRetarget.apply(fixture.transformer, MIXIN, mixinNode(NEW_TARGET), null, null, null);

		assertNull(affected, "an unregistered mixin must not be retargeted");
		assertTrue(fixture.config.mixins.isEmpty());
		assertTrue(fixture.config.mixinMapping.isEmpty());
	}

	// ------------------------------------------------------------------ fakes

	private static ClassNode mixinNode(String target) {
		ClassNode node = new ClassNode();
		node.version = 52;
		node.name = "com/example/mixin/ProbeMixin";
		node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		mixin.values.add("value");
		mixin.values.add(Type.getObjectType(target));
		node.invisibleAnnotations = new ArrayList<>();
		node.invisibleAnnotations.add(mixin);
		return node;
	}

	private static final class Fixture {
		final FakeTransformer transformer = new FakeTransformer();
		final FakeConfig config = new FakeConfig();

		Fixture(List<String> targets) {
			FakeInfo info = new FakeInfo(targets);
			this.config.mixins.add(info);
			this.config.mixinClasses.add("ProbeMixin");
			for (String target : targets) {
				this.config.mixinMapping.computeIfAbsent(target.replace('/', '.'),
					key -> new ArrayList<>()).add(info);
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

		List<String> nextTargets = List.of();
		boolean failPrepare;

		@SuppressWarnings("unused")
		private boolean prepared = true;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "example_mod.mixins.json";
		}

		int getDefaultMixinPriority() {
			return 1000;
		}

		/** 与上游同构：按 {@code mixinClasses} 里尚未登记的名字创建新的 {@code MixinInfo}。 */
		@SuppressWarnings("unused")
		private void prepare(Object extensions) {
			if (this.failPrepare) {
				throw new IllegalStateException("simulated prepare failure");
			}
			for (String simpleName : new ArrayList<>(this.mixinClasses)) {
				String className = MIXIN_PACKAGE + simpleName;
				if (find(className) != null) {
					continue;
				}
				FakeInfo info = new FakeInfo(this.nextTargets);
				this.mixins.add(info);
				for (String target : this.nextTargets) {
					String dotted = target.replace('/', '.');
					this.mixinMapping.computeIfAbsent(dotted, key -> new ArrayList<>()).add(info);
					this.unhandledTargets.add(dotted);
				}
				this.globalMixinList.add(className);
			}
			this.prepared = true;
		}

		FakeInfo find(String className) {
			for (FakeInfo info : this.mixins) {
				if (info.getClassName().equals(className)) {
					return info;
				}
			}
			return null;
		}
	}

	private static final class FakeInfo {
		private final List<String> targets;

		FakeInfo(List<String> targets) {
			this.targets = new ArrayList<>(targets);
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

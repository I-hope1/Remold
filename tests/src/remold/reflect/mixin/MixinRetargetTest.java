package remold.reflect.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
 * {@link MixinRetarget} 的契约测试：目标元数据迁移、配置索引迁移，以及 reload 失败后的完整回滚。
 *
 * <p>用与 Mixin 0.8.5 同构的 fake 配置验证反射层；{@code ClassInfo} 通过可注入解析器提供
 * （真实 {@code ClassInfo.forName} 需要 Mixin 服务与目标字节，普通 JUnit 拿不到）。
 * 真实 Mixin 上的端到端行为由 Fabric 真机探针覆盖。</p>
 */
class MixinRetargetTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";
	private static final String MIXIN = MIXIN_PACKAGE + "ProbeMixin";
	private static final String OLD_TARGET = "com/example/OldTarget";
	private static final String NEW_TARGET = "com/example/NewTarget";

	@Test
	void applyMigratesTargetMetadataAndConfigIndexes() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		ClassNode node = mixinNode(NEW_TARGET);

		MixinRetarget.Result result = fixture.apply(node);

		assertNotNull(result, "a well-formed target change must migrate");
		assertEquals(Set.of("com.example.OldTarget", "com.example.NewTarget"),
			new LinkedHashSet<>(result.affected()),
			"both the removed and the added target must be re-woven");
		assertEquals(List.of(NEW_TARGET), fixture.info.targetClassNames,
			"MixinInfo.targetClassNames must carry the new target (internal names)");
		assertEquals(List.of(NEW_TARGET), fixture.info.getTargetClasses());
		assertFalse(fixture.config.mixinMapping.containsKey("com.example.OldTarget"),
			"the old target mapping must be gone so a re-weave drops the mixin injections");
		assertTrue(fixture.config.mixinMapping.getOrDefault("com.example.NewTarget", List.of())
			.contains(fixture.info));
		assertTrue(fixture.config.unhandledTargets.contains("com.example.NewTarget"));
		assertFalse(fixture.config.unhandledTargets.contains("com.example.OldTarget"));
		assertTrue(fixture.removedFromOldClassInfo,
			"the old target ClassInfo.mixins must not keep the migrated MixinInfo");
	}

	@Test
	void rollbackRestoresTargetsAndMappingAfterARejectedReload() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		MixinRetarget.Result result = fixture.apply(mixinNode(NEW_TARGET));
		assertNotNull(result);

		MixinRetarget.rollback(fixture.transformer, MIXIN, result);

		assertEquals(List.of(OLD_TARGET), fixture.info.targetClassNames,
			"a rejected reload must restore the old target metadata");
		assertEquals(List.of(OLD_TARGET), fixture.info.getTargetClasses());
		assertTrue(fixture.config.mixinMapping.getOrDefault("com.example.OldTarget", List.of())
			.contains(fixture.info), "the old target mapping must be restored");
		assertFalse(fixture.config.mixinMapping.containsKey("com.example.NewTarget"));
		assertTrue(fixture.config.unhandledTargets.contains("com.example.OldTarget"));
		assertFalse(fixture.config.unhandledTargets.contains("com.example.NewTarget"));
	}

	@Test
	void unchangedTargetsAreNotMigrated() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));

		MixinRetarget.Result result = fixture.apply(mixinNode(OLD_TARGET));

		assertNull(result, "an unchanged target set must fall through to the plain reload path");
		assertEquals(List.of(OLD_TARGET), fixture.info.targetClassNames);
	}

	@Test
	void unresolvableNewTargetIsNotMigrated() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		fixture.resolveUnknownTargets = true;

		MixinRetarget.Result result = fixture.apply(mixinNode(NEW_TARGET));

		assertNull(result, "an unresolvable new target must not half-migrate the metadata");
		assertEquals(List.of(OLD_TARGET), fixture.info.targetClassNames);
		assertTrue(fixture.config.mixinMapping.getOrDefault("com.example.OldTarget", List.of())
			.contains(fixture.info));
	}

	@Test
	void missingMixinInfoIsNotTouched() {
		Fixture fixture = new Fixture(List.of(OLD_TARGET));
		fixture.config.mixins.clear();
		fixture.config.mixinMapping.clear();
		fixture.config.unhandledTargets.clear();

		MixinRetarget.Result result = fixture.apply(mixinNode(NEW_TARGET));

		assertNull(result, "an unregistered mixin must not be migrated");
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
		final FakeInfo info;
		final Map<String, Object> classInfos = new LinkedHashMap<>();
		boolean resolveUnknownTargets;
		boolean removedFromOldClassInfo;

		Fixture(List<String> targets) {
			this.info = new FakeInfo(targets);
			this.config.mixins.add(this.info);
			this.config.mixinClasses.add("ProbeMixin");
			for (String target : targets) {
				this.config.mixinMapping.computeIfAbsent(target.replace('/', '.'),
					key -> new ArrayList<>()).add(this.info);
				this.config.unhandledTargets.add(target.replace('/', '.'));
			}
			this.config.globalMixinList.add(MIXIN);
			this.transformer.processor = new FakeProcessor(Collections.singletonList(this.config));
			// 旧目标的 ClassInfo 预先登记该 MixinInfo，供“旧目标元数据必须被摘除”断言使用。
			FakeClassInfo old = (FakeClassInfo) this.classInfos.computeIfAbsent(OLD_TARGET, FakeClassInfo::new);
			old.mixins.add(this.info);
		}

		MixinRetarget.Result apply(ClassNode node) {
			Function<String, Object> resolver = internal -> {
				if (this.resolveUnknownTargets) {
					return null;
				}
				return this.classInfos.computeIfAbsent(internal, FakeClassInfo::new);
			};
			MixinRetarget.Result result = MixinRetarget.apply(this.transformer, MIXIN, node,
				null, null, null, resolver);
			if (result != null) {
				FakeClassInfo old = (FakeClassInfo) this.classInfos.get(OLD_TARGET);
				this.removedFromOldClassInfo = old != null && !old.mixins.contains(this.info);
			}
			return result;
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
		final Set<String> globalMixinList = new LinkedHashSet<>();

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
	}

	private static final class FakeClassInfo {
		final Set<Object> mixins = new LinkedHashSet<>();
		final String name;

		FakeClassInfo(String name) {
			this.name = name;
		}
	}

	private static final class FakeInfo {
		final List<Object> targetClasses = new ArrayList<>();
		final List<Object> targetClassNames = new ArrayList<>();
		final List<Object> declaredTargets = new ArrayList<>();

		FakeInfo(List<String> targets) {
			this.targetClassNames.addAll(targets);
		}

		String getClassName() {
			return MIXIN;
		}

		List<String> getTargetClasses() {
			List<String> out = new ArrayList<>();
			for (Object name : this.targetClassNames) {
				out.add(String.valueOf(name));
			}
			return out;
		}

		@SuppressWarnings("unused")
		private void validate() {
		}
	}
}

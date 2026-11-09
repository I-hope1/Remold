package remold.reflect.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core 反射配置差异引擎的契约测试：删除后从所有 MixinConfig 内部集合移除并调度目标，
 * 重新加入后走同一登记路径；截断配置必须保持现状。
 */
class MixinConfigDifferTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";

	@Test
	void removesDeclaredMixinAndSchedulesItsTargets(@TempDir Path tempDir) throws Exception {
		Path configFile = tempDir.resolve("example_mod.mixins.json");
		Files.writeString(configFile, "{\"mixins\": [\"ExistingMixin\"]}", StandardCharsets.UTF_8);

		FakeConfig config = new FakeConfig();
		config.add("ExistingMixin");
		config.add("RemovableMixin");
		List<String> scheduled = new ArrayList<>();
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config, scheduled));

		assertFalse(config.mixinClasses.contains("RemovableMixin"));
		assertFalse(config.globalMixinList.contains(MIXIN_PACKAGE + "RemovableMixin"));
		assertNotNull(config.find(MIXIN_PACKAGE + "ExistingMixin"));
		assertEquals(List.of("com.example.target.RemovableTarget"), scheduled);
		assertTrue(MixinConfigDiffer.isRemovedFromConfig(MIXIN_PACKAGE + "RemovableMixin"));
	}

	@Test
	void readdingTheNameRegistersItAgain(@TempDir Path tempDir) throws Exception {
		Path configFile = tempDir.resolve("example_mod.mixins.json");
		Files.writeString(configFile, "{\"mixins\": []}", StandardCharsets.UTF_8);
		FakeConfig config = new FakeConfig();
		config.add("RemovableMixin");
		List<String> scheduled = new ArrayList<>();
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config, scheduled));
		assertFalse(config.mixinClasses.contains("RemovableMixin"));

		Files.writeString(configFile, "{\"mixins\": [\"RemovableMixin\"]}", StandardCharsets.UTF_8);
		scheduled.clear();
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config, scheduled));

		assertTrue(config.mixinClasses.contains("RemovableMixin"));
		assertNotNull(config.find(MIXIN_PACKAGE + "RemovableMixin"));
		assertEquals(List.of("com.example.target.RemovableTarget"), scheduled);
		assertFalse(MixinConfigDiffer.isRemovedFromConfig(MIXIN_PACKAGE + "RemovableMixin"));
	}

	@Test
	void malformedConfigLeavesCurrentStateUntouched(@TempDir Path tempDir) throws Exception {
		Path configFile = tempDir.resolve("example_mod.mixins.json");
		Files.writeString(configFile, "{\"mixins\": [\"RemovableMixin\"", StandardCharsets.UTF_8);
		FakeConfig config = new FakeConfig();
		config.add("RemovableMixin");
		List<String> scheduled = new ArrayList<>();

		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config, scheduled));

		assertTrue(config.mixinClasses.contains("RemovableMixin"));
		assertNotNull(config.find(MIXIN_PACKAGE + "RemovableMixin"));
		assertTrue(scheduled.isEmpty());
	}

	private static MixinConfigContext context(FakeConfig config, List<String> scheduled) {
		FakeTransformer transformer = new FakeTransformer();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));
		return new MixinConfigContext(
			transformer,
			"CLIENT",
			dot -> mixinNode(),
			targets -> new Object(),
			token -> {
			},
			"Mixin test class tracker",
			scheduled::addAll);
	}

	private static ClassNode mixinNode() {
		ClassNode node = new ClassNode();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		mixin.values.add("value");
		mixin.values.add(Type.getObjectType("com/example/target/RemovableTarget"));
		node.invisibleAnnotations = new ArrayList<>();
		node.invisibleAnnotations.add(mixin);
		return node;
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
		private static final Set<String> GLOBAL = new HashSet<>();
		private final List<String> mixinClasses = new ArrayList<>();
		private final List<FakeInfo> mixins = new ArrayList<>();
		private final Map<String, List<FakeInfo>> mixinMapping = new LinkedHashMap<>();
		private final Set<String> unhandledTargets = new LinkedHashSet<>();
		private final Set<String> globalMixinList = GLOBAL;

		@SuppressWarnings("unused")
		private boolean prepared;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "example_mod.mixins.json";
		}

		void add(String simpleName) {
			mixinClasses.add(simpleName);
			mixins.add(new FakeInfo(MIXIN_PACKAGE + simpleName));
			mixinMapping.computeIfAbsent("com.example.target.RemovableTarget",
				key -> new ArrayList<>()).add(mixins.get(mixins.size() - 1));
			unhandledTargets.add("com.example.target.RemovableTarget");
			globalMixinList.add(MIXIN_PACKAGE + simpleName);
		}

		FakeInfo find(String className) {
			for (FakeInfo info : mixins) {
				if (info.getClassName().equals(className)) {
					return info;
				}
			}
			return null;
		}

		@SuppressWarnings("unused")
		private void prepare(Object extensions) {
			for (String simpleName : new ArrayList<>(mixinClasses)) {
				String className = MIXIN_PACKAGE + simpleName;
				if (find(className) != null) {
					continue;
				}
				FakeInfo info = new FakeInfo(className);
				mixins.add(info);
				mixinMapping.computeIfAbsent("com.example.target.RemovableTarget",
					key -> new ArrayList<>()).add(info);
				unhandledTargets.add("com.example.target.RemovableTarget");
				globalMixinList.add(className);
			}
			prepared = true;
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
			return List.of("com.example.target.RemovableTarget");
		}

		@SuppressWarnings("unused")
		private void validate() {
		}
	}
}

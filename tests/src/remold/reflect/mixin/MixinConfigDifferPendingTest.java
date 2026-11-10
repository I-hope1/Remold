package remold.reflect.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
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
 * 「配置先到、字节后到」的接力登记契约测试。
 *
 * <p>IDE 里先保存 {@code mixins.json}、几秒后才编译出 {@code .class} 是常态。资源事件那一刻拿不到
 * 字节的条目必须进入待决集，并在后续类变更时自动完成登记——既不要求重启，也不要求用户再存一次配置。
 * 本测试用可控的 {@code classNodeLoader} 精确控制“字节何时可用”。</p>
 */
class MixinConfigDifferPendingTest {

	private static final String MIXIN_PACKAGE = "com.example.mixin.";
	private static final String DEFERRED = "DeferredMixin";
	private static final String DEFERRED_DOT = MIXIN_PACKAGE + DEFERRED;
	private static final String TARGET = "com/example/target/DeferredTarget";

	private final AtomicReference<ClassNode> availableNode = new AtomicReference<>();
	private final AtomicInteger loaderCalls = new AtomicInteger();
	private final List<String> scheduled = new ArrayList<>();

	@BeforeEach
	void resetGlobalState() {
		MixinConfigDiffer.clearPendingUnresolvedMixins();
		this.availableNode.set(null);
		this.loaderCalls.set(0);
		this.scheduled.clear();
	}

	@Test
	void configFirstBytecodeLaterRegistersAutomatically(@TempDir Path tempDir) throws Exception {
		Path configFile = writeConfig(tempDir, DEFERRED);
		FakeConfig config = new FakeConfig();

		// Phase 1: the config lists a mixin whose .class is not compiled yet.
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config));

		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().contains(DEFERRED_DOT),
			"the unresolved name must be remembered, not silently consumed");
		assertFalse(config.mixinClasses.contains(DEFERRED), "nothing can be registered without bytecode");
		assertTrue(this.scheduled.isEmpty());

		// Phase 2: the class batch arrives but the bytecode is still unavailable.
		MixinConfigDiffer.retryPending(context(config));
		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().contains(DEFERRED_DOT),
			"a retry without bytecode must keep the entry pending");

		// Phase 3: the .class lands; the next retry must complete the registration by itself.
		this.availableNode.set(mixinNode());
		MixinConfigDiffer.retryPending(context(config));

		assertFalse(MixinConfigDiffer.pendingUnresolvedMixins().contains(DEFERRED_DOT),
			"a successful registration must consume the pending entry");
		assertTrue(config.mixinClasses.contains(DEFERRED), "the mixin must be registered into the config");
		assertNotNull(config.find(DEFERRED_DOT));
		assertTrue(config.mixinMapping.getOrDefault("com.example.target.DeferredTarget", List.of())
			.contains(config.find(DEFERRED_DOT)), "the target mapping must be updated");
		assertEquals(List.of("com.example.target.DeferredTarget"), this.scheduled,
			"the affected target must be scheduled for re-weaving");
	}

	@Test
	void aMixinThatIsAlreadyRegisteredNeverBecomesPending(@TempDir Path tempDir) throws Exception {
		Path configFile = writeConfig(tempDir, DEFERRED);
		FakeConfig config = new FakeConfig();
		config.add(DEFERRED);

		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config));

		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().isEmpty(),
			"an already registered mixin must not be treated as deferred");
		assertEquals(0, this.loaderCalls.get(), "the bytecode loader must not even be consulted");
	}

	@Test
	void droppingTheNameFromTheConfigForgetsThePendingEntry(@TempDir Path tempDir) throws Exception {
		Path configFile = writeConfig(tempDir, DEFERRED);
		FakeConfig config = new FakeConfig();
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config));
		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().contains(DEFERRED_DOT));

		// The user removes the entry again before the class was ever compiled.
		Files.writeString(configFile, "{\"mixins\": []}", StandardCharsets.UTF_8);
		this.availableNode.set(mixinNode());
		MixinConfigDiffer.retryPending(context(config));

		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().isEmpty(),
			"a name no longer listed in the config must not be registered later");
		assertFalse(config.mixinClasses.contains(DEFERRED));
	}

	@Test
	void retriesAreBoundedAndGiveUpLoudly(@TempDir Path tempDir) throws Exception {
		Path configFile = writeConfig(tempDir, DEFERRED);
		FakeConfig config = new FakeConfig();
		MixinConfigDiffer.onAssetsChanged(Set.of(configFile), context(config));

		for (int i = 0; i <= MixinConfigDiffer.PENDING_MAX_ATTEMPTS; i++) {
			MixinConfigDiffer.retryPending(context(config));
		}

		assertTrue(MixinConfigDiffer.pendingUnresolvedMixins().isEmpty(),
			"an entry that never gets bytecode must be dropped after the attempt budget");
		assertFalse(config.mixinClasses.contains(DEFERRED));
	}

	// ------------------------------------------------------------------ fixtures

	private Path writeConfig(Path dir, String simpleName) throws Exception {
		Path file = dir.resolve("deferred.mixins.json");
		Files.writeString(file, "{\"mixins\": [\"" + simpleName + "\"]}", StandardCharsets.UTF_8);
		return file;
	}

	private MixinConfigContext context(FakeConfig config) {
		FakeTransformer transformer = new FakeTransformer();
		transformer.processor = new FakeProcessor(Collections.singletonList(config));
		Function<String, Object> loader = dot -> {
			this.loaderCalls.incrementAndGet();
			return this.availableNode.get();
		};
		return new MixinConfigContext(transformer, "CLIENT", loader,
			targets -> new Object(), token -> {
			}, "Mixin test class tracker", this.scheduled::addAll);
	}

	private static ClassNode mixinNode() {
		ClassNode node = new ClassNode();
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>();
		mixin.values.add("value");
		mixin.values.add(Type.getObjectType(TARGET));
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
		private final List<String> mixinClasses = new ArrayList<>();
		private final List<FakeInfo> mixins = new ArrayList<>();
		private final Map<String, List<FakeInfo>> mixinMapping = new LinkedHashMap<>();
		private final Set<String> unhandledTargets = new LinkedHashSet<>();
		private final Set<String> globalMixinList = new LinkedHashSet<>();

		@SuppressWarnings("unused")
		private boolean prepared;

		String getMixinPackage() {
			return MIXIN_PACKAGE;
		}

		String getName() {
			return "deferred.mixins.json";
		}

		int getDefaultMixinPriority() {
			return 1000;
		}

		void add(String simpleName) {
			this.mixinClasses.add(simpleName);
			FakeInfo info = new FakeInfo(MIXIN_PACKAGE + simpleName);
			this.mixins.add(info);
			this.mixinMapping.computeIfAbsent("com.example.target.DeferredTarget",
				key -> new ArrayList<>()).add(info);
			this.unhandledTargets.add("com.example.target.DeferredTarget");
			this.globalMixinList.add(MIXIN_PACKAGE + simpleName);
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
			for (String simpleName : new ArrayList<>(this.mixinClasses)) {
				String className = MIXIN_PACKAGE + simpleName;
				if (find(className) != null) {
					continue;
				}
				FakeInfo info = new FakeInfo(className);
				this.mixins.add(info);
				this.mixinMapping.computeIfAbsent("com.example.target.DeferredTarget",
					key -> new ArrayList<>()).add(info);
				this.unhandledTargets.add("com.example.target.DeferredTarget");
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
			return this.className;
		}

		List<String> getTargetClasses() {
			return List.of("com/example/target/DeferredTarget");
		}

		@SuppressWarnings("unused")
		private void validate() {
		}
	}
}

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.HotswapPlugin;
import remold.forge.ForgeEventBusRefresher;
import remold.forge.ForgeHotswapPlugin;
import remold.forge.ForgeRuntimeDetector;
import remold.forge.ModLauncherMixinBridge;
import remold.forge.NeoForgeHotswapPlugin;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Forge & NeoForge Plugin Suite")
public class ForgePluginTest {

	@Retention(RetentionPolicy.RUNTIME)
	public @interface SubscribeEvent {}

	@Retention(RetentionPolicy.RUNTIME)
	public @interface EventBusSubscriber {}

	@EventBusSubscriber
	public static class SampleEventHandler {
		@SubscribeEvent
		public static void onCustomEvent(Object event) {}

		public static void plainMethod() {}
	}

	public static class PlainClass {
		public void normalMethod() {}
	}

	@Test
	@DisplayName("Forge and NeoForge plugins have correct names and ServiceLoader discovers them")
	public void testPluginNamesAndServiceLoader() {
		ForgeHotswapPlugin forge = new ForgeHotswapPlugin();
		assertEquals("forge", forge.getName());

		NeoForgeHotswapPlugin neoforge = new NeoForgeHotswapPlugin();
		assertEquals("neoforge", neoforge.getName());

		boolean foundForge = false;
		boolean foundNeoForge = false;
		ServiceLoader<HotswapPlugin> loader = ServiceLoader.load(HotswapPlugin.class, ForgeHotswapPlugin.class.getClassLoader());
		for (HotswapPlugin plugin : loader) {
			if ("forge".equals(plugin.getName())) foundForge = true;
			if ("neoforge".equals(plugin.getName())) foundNeoForge = true;
		}
		assertTrue(foundForge, "ServiceLoader should discover ForgeHotswapPlugin");
		assertTrue(foundNeoForge, "ServiceLoader should discover NeoForgeHotswapPlugin");
	}

	@Test
	@DisplayName("ForgeRuntimeDetector safely handles missing classes without exceptions")
	public void testRuntimeDetectorAbsence() {
		assertFalse(ForgeRuntimeDetector.isForge(null));
		assertFalse(ForgeRuntimeDetector.isNeoForge(null));
		assertNull(ForgeRuntimeDetector.findClass("non.existent.Class", null));
		assertNotNull(ForgeRuntimeDetector.findClass(String.class.getName(), null));
	}

	@Test
	@DisplayName("ModLauncherMixinBridge bypasses null, empty, and mixin classes safely")
	public void testModLauncherMixinBridgePassThrough() {
		ModLauncherMixinBridge bridge = new ModLauncherMixinBridge(null);
		byte[] dummy = "dummy bytecode content".getBytes(StandardCharsets.UTF_8);

		// Null inputs
		assertNull(bridge.transform(null, null, null, null));
		assertSame(dummy, bridge.transform(null, "TestClass", null, dummy));

		// Bytecode containing @Mixin descriptor should be recognized and passed through
		byte[] mixinBytes = "Lorg/spongepowered/asm/mixin/Mixin; extra bytecode".getBytes(StandardCharsets.UTF_8);
		assertSame(mixinBytes, bridge.transform(null, "MyMixin", null, mixinBytes));
	}

	@Test
	@DisplayName("ForgeEventBusRefresher safely inspects classes and handles batch callback")
	public void testEventBusRefresherInspection() {
		ForgeEventBusRefresher refresher = new ForgeEventBusRefresher(null);

		// Empty batch does nothing
		assertDoesNotThrow(() -> refresher.afterInternalBatch(Collections.emptyList()));
		assertDoesNotThrow(() -> refresher.onExternalClassRedefined(null, null));

		// Class with @SubscribeEvent and @EventBusSubscriber does not throw even when no bus is loaded
		assertDoesNotThrow(() -> refresher.onExternalClassRedefined(SampleEventHandler.class, new byte[0]));
		assertDoesNotThrow(() -> refresher.onExternalClassRedefined(PlainClass.class, new byte[0]));
	}
}

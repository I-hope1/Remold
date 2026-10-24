import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.HotswapPlugin;
import remold.RemoldAgent;
import remold.arc.AtlasRegionSync;
import remold.arc.BundleReloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Asset Hot-Reloading Suite")
public class AssetReloaderTest {

	@Test
	@DisplayName("isAssetFile correctly filters assets vs classes, jars, and temp files")
	public void testIsAssetFile() {
		// Valid assets
		assertTrue(RemoldAgent.isAssetFile(Paths.get("sprites/copper-wall.png")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("sprites/texture.jpg")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("bundles/bundle.properties")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("bundles/bundle_zh_CN.properties")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("content/items.json")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("content/blocks.hjson")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("sounds/shoot.ogg")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("sounds/boom.wav")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("fonts/font.woff")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("fonts/font.ttf")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("fonts/font.fnt")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("sprites/sprites.atlas")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("shaders/bloom.frag")));
		assertTrue(RemoldAgent.isAssetFile(Paths.get("shaders/bloom.vert")));

		// Non-assets
		assertFalse(RemoldAgent.isAssetFile(null));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("remold/RemoldAgent.class")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("mods/mymod.jar")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("mods/bundle.zip")));

		// Temp / hidden files
		assertFalse(RemoldAgent.isAssetFile(Paths.get(".hidden.png")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("copper-wall.png.tmp")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("copper-wall.png.swp")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("copper-wall.png.bak")));
		assertFalse(RemoldAgent.isAssetFile(Paths.get("copper-wall.png~")));
	}

	@Test
	@DisplayName("RemoldAgent dispatches onAssetsChanged to registered plugins")
	public void testPluginAssetDispatch() {
		AtomicBoolean received = new AtomicBoolean(false);
		Set<Path> receivedPaths = new HashSet<>();

		HotswapPlugin testPlugin = new HotswapPlugin() {
			@Override
			public String getName() {
				return "test-asset-plugin";
			}

			@Override
			public void onAssetsChanged(Set<Path> changedAssets) {
				received.set(true);
				receivedPaths.addAll(changedAssets);
			}
		};

		RemoldAgent.registerPlugin(testPlugin);
		try {
			Path testAsset = Paths.get("sprites/test-sprite.png");
			RemoldAgent.notifyAssetChanges(Collections.singleton(testAsset));

			assertTrue(received.get(), "Plugin should have received onAssetsChanged callback");
			assertTrue(receivedPaths.contains(testAsset), "Plugin should have received exact changed asset path");
		} finally {
			RemoldAgent.unregisterPlugin(testPlugin);
		}
	}

	@Test
	@DisplayName("AtlasRegionSync preserves all AtlasRegion metadata and clone arrays")
	public void testAtlasRegionSync() {
		AtlasRegion src = new AtlasRegion();
		src.name = "test-icon";
		src.splits = new int[]{1, 2, 3, 4};
		src.pads = new int[]{5, 6, 7, 8};
		src.offsetX = 12.5f;
		src.offsetY = 24.5f;
		src.packedWidth = 64;
		src.packedHeight = 64;
		src.originalWidth = 128;
		src.originalHeight = 128;
		src.rotate = true;

		AtlasRegion dest = new AtlasRegion();
		AtlasRegionSync.sync(dest, src);

		assertEquals("test-icon", dest.name);
		assertNotNull(dest.splits);
		assertArrayEquals(new int[]{1, 2, 3, 4}, dest.splits);
		assertNotSame(src.splits, dest.splits, "Splits array must be cloned, not reference-shared");

		assertNotNull(dest.pads);
		assertArrayEquals(new int[]{5, 6, 7, 8}, dest.pads);
		assertNotSame(src.pads, dest.pads, "Pads array must be cloned, not reference-shared");

		assertEquals(12.5f, dest.offsetX, 0.001f);
		assertEquals(24.5f, dest.offsetY, 0.001f);
		assertEquals(64, dest.packedWidth);
		assertEquals(64, dest.packedHeight);
		assertEquals(128, dest.originalWidth);
		assertEquals(128, dest.originalHeight);
		assertTrue(dest.rotate);
	}

	@Test
	@DisplayName("BundleReloader safely handles missing files and null bundles")
	public void testBundleReloaderSafety() {
		assertFalse(BundleReloader.reloadBundle(null));
		assertFalse(BundleReloader.reloadBundle(Paths.get("non-existent-bundle.properties")));
	}
}

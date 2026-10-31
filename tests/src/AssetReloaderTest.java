import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.gl.Shader;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.graphics.Shaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.HotswapPlugin;
import remold.RemoldAgent;
import remold.arc.ArcAssetReloader;
import remold.arc.ArcAssetReloader.AssetKind;
import remold.arc.AtlasRegionSync;
import remold.arc.BundleReloader;
import remold.arc.FontReloader;
import remold.arc.MusicReloader;
import remold.arc.ShaderReloader;
import remold.arc.SoundReloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
	@DisplayName("ArcAssetReloader classifies assets into the correct reload path")
	public void testAssetClassification() {
		assertEquals(AssetKind.TEXTURE, ArcAssetReloader.classify(Paths.get("sprites/copper-wall.png")));
		assertEquals(AssetKind.FONT, ArcAssetReloader.classify(Paths.get("fonts/font.woff")));
		assertEquals(AssetKind.BUNDLE, ArcAssetReloader.classify(Paths.get("bundles/bundle_zh_CN.properties")));
		assertEquals(AssetKind.DATA, ArcAssetReloader.classify(Paths.get("icons/icons.properties")),
			"Non-bundle properties must not be sent to BundleReloader");
		assertEquals(AssetKind.SOUND, ArcAssetReloader.classify(Paths.get("sounds/block/boom.ogg")));
		assertEquals(AssetKind.MUSIC, ArcAssetReloader.classify(Paths.get("mods/example/music/game1.ogg")));
		assertEquals(AssetKind.SHADER, ArcAssetReloader.classify(Paths.get("shaders/bloom.frag")));
		assertEquals(AssetKind.CONTENT, ArcAssetReloader.classify(Paths.get("content/blocks.hjson")));
		assertEquals(AssetKind.ATLAS, ArcAssetReloader.classify(Paths.get("sprites/sprites.atlas")));
		assertEquals(AssetKind.DATA, ArcAssetReloader.classify(Paths.get("data/table.csv")));
		assertEquals(AssetKind.OTHER, ArcAssetReloader.classify(Paths.get("remold/RemoldAgent.class")));
		assertEquals(AssetKind.OTHER, ArcAssetReloader.classify(null));
	}

	@Test
	@DisplayName("FontReloader maps standard font files to every font slot that uses them")
	public void testFontFileRouting() {
		assertArrayEquals(new String[]{"def", "outline"},
			FontReloader.standardSlots(Paths.get("fonts/font.woff")));
		assertArrayEquals(new String[]{"icon", "iconLarge"},
			FontReloader.standardSlots(Paths.get("fonts/icon.ttf")));
		assertArrayEquals(new String[]{"monospace"},
			FontReloader.standardSlots(Paths.get("fonts/monospace.woff")));
		assertArrayEquals(new String[]{"logic"},
			FontReloader.standardSlots(Paths.get("fonts/logic.ttf")));
		assertArrayEquals(new String[]{"tech"},
			FontReloader.standardSlots(Paths.get("fonts/tech.ttf")));
		assertNull(FontReloader.standardSlots(Paths.get("fonts/custom.ttf")),
			"Unknown font files must fall back to generator-path matching");
		assertNull(FontReloader.standardSlots(null));
	}

	@Test
	@DisplayName("SoundReloader extracts sounds/ keys and ignores music paths")
	public void testSoundPathParsing() {
		assertEquals("shoot", SoundReloader.soundName(Paths.get("sounds/shoot.ogg")));
		assertEquals("block/boom", SoundReloader.soundName(Paths.get("mods/example/sounds/block/boom.wav")));
		assertNull(SoundReloader.soundName(Paths.get("music/game1.ogg")));
		assertNull(SoundReloader.soundName(null));
	}

	@Test
	@DisplayName("MusicReloader extracts music/ keys and ignores sound paths")
	public void testMusicPathParsing() {
		assertEquals("game1", MusicReloader.musicName(Paths.get("music/game1.ogg")));
		assertEquals("boss/boss1", MusicReloader.musicName(Paths.get("mods/example/music/boss/boss1.mp3")));
		assertNull(MusicReloader.musicName(Paths.get("sounds/shoot.ogg")));
		assertNull(MusicReloader.musicName(null));
	}

	@Test
	@DisplayName("ShaderReloader maps shader files to every built-in field that uses them")
	public void testShaderFileMapping() {
		assertEquals(Set.of("blockbuild", "build", "armor", "darkness", "fog"),
			new HashSet<>(ShaderReloader.affectedFields("default.vert")));
		assertEquals(Set.of("planet"),
			new HashSet<>(ShaderReloader.affectedFields("planet.vert")));
		assertEquals(Set.of("planet", "clouds", "mesh", "unlit", "unlitWhite"),
			new HashSet<>(ShaderReloader.affectedFields("planet.frag")));
		assertEquals(Set.of("shield", "buildBeam", "light", "water", "mud", "tar", "slag",
				"cryofluid", "arkycite", "space", "caustics", "shockwave", "screenspace"),
			new HashSet<>(ShaderReloader.affectedFields("screenspace.vert")));
		assertEquals(List.of("clouds.vert", "planet.frag"), ShaderReloader.shaderFiles("clouds"));
		assertEquals(List.of(), ShaderReloader.affectedFields("custom.frag"));
		assertEquals(List.of(), ShaderReloader.affectedFields(null));
	}

	@Test
	@DisplayName("ShaderReloader covers every built-in Shader static field")
	public void testShaderBindingsCoverBuiltIns() {
		for (Field field : Shaders.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || !Shader.class.isAssignableFrom(field.getType())) continue;
			assertTrue(ShaderReloader.hasBinding(field.getName()),
				"Missing shader binding for Shaders." + field.getName());
		}
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

	@Test
	@DisplayName("BundleReloader refreshes cached content localization and preserves derived names")
	public void testBundleReloaderRefreshesContentLocalization() throws Exception {
		I18NBundle originalBundle = Core.bundle;
		ContentLoader originalContent = Vars.content;
		Path tempDir = Files.createTempDirectory("remold-bundle-");
		Path bundleFile = tempDir.resolve("bundle.properties");

		try {
			Core.bundle = I18NBundle.createEmptyBundle();
			Vars.content = new ContentLoader();

			Core.bundle.getProperties().put("item.test-item.name", "Old Name");
			Core.bundle.getProperties().put("item.test-item.description", "Old Description");

			UnlockableContent item = testContent("test-item");

			// Baseline: the constructor caches the bundle values, so the test can prove they change.
			assertEquals("Old Name", item.localizedName);
			assertEquals("Old Description", item.description);

			UnlockableContent derived = testContent("derived-item");
			derived.localizedName = "Derived Name";

			Files.writeString(bundleFile,
				"item.test-item.name=New Name\nitem.test-item.description=New Description\n",
				StandardCharsets.UTF_8);

			assertTrue(BundleReloader.reloadBundle(bundleFile));

			assertEquals("New Name", item.localizedName);
			assertEquals("New Description", item.description);
			assertEquals("Derived Name", derived.localizedName,
				"Programmatically derived localized names must not be overwritten by bundle reload");
		} finally {
			Core.bundle = originalBundle;
			Vars.content = originalContent;
			deleteTree(tempDir);
		}
	}

	@Test
	@DisplayName("BundleReloader routes base and locale bundle files independently")
	public void testBundleReloaderLocaleRouting() throws Exception {
		I18NBundle originalBundle = Core.bundle;
		ContentLoader originalContent = Vars.content;
		Path tempDir = Files.createTempDirectory("remold-bundle-locale-");

		try {
			Path baseFile = tempDir.resolve("bundle.properties");
			Path zhFile = tempDir.resolve("bundle_zh_CN.properties");
			Files.writeString(baseFile,
				"item.test-item.name=Root Name\nitem.root-only.name=Root Only\n",
				StandardCharsets.UTF_8);
			Files.writeString(zhFile, "item.test-item.name=中文名\n", StandardCharsets.UTF_8);

			Core.bundle = I18NBundle.createBundle(
				new Fi(tempDir.resolve("bundle").toFile()), Locale.SIMPLIFIED_CHINESE);
			Vars.content = new ContentLoader();

			assertEquals("zh_CN", Core.bundle.getLocale().toString());
			I18NBundle base = Core.bundle.getParent();
			assertNotNull(base, "Test setup must produce a base -> locale bundle chain");

			UnlockableContent item = testContent("test-item");
			UnlockableContent rootOnly = testContent("root-only");

			assertEquals("中文名", item.localizedName);
			assertEquals("Root Only", rootOnly.localizedName);

			Files.writeString(zhFile, "item.test-item.name=新名字\n", StandardCharsets.UTF_8);
			assertTrue(BundleReloader.reloadBundle(zhFile));

			assertEquals("新名字", item.localizedName);
			assertEquals("Root Name", base.getProperties().get("item.test-item.name"),
				"Locale reload must not pollute the base bundle");
			assertFalse(Core.bundle.getProperties().containsKey("item.root-only.name"),
				"Locale bundle must not receive base-only keys");

			Files.writeString(baseFile,
				"item.test-item.name=Root Name 2\nitem.root-only.name=Root Only 2\n",
				StandardCharsets.UTF_8);
			assertTrue(BundleReloader.reloadBundle(baseFile));

			assertEquals("Root Only 2", rootOnly.localizedName);
			assertEquals("新名字", item.localizedName,
				"Locale-specific value must keep overriding the base bundle");
			assertFalse(Core.bundle.getProperties().containsKey("item.root-only.name"),
				"Base reload must not write into the locale bundle");
		} finally {
			Core.bundle = originalBundle;
			Vars.content = originalContent;
			deleteTree(tempDir);
		}
	}

	private static UnlockableContent testContent(String name) {
		return new UnlockableContent(name) {
			@Override
			public ContentType getContentType() {
				return ContentType.item;
			}
		};
	}

	private static void deleteTree(Path root) throws Exception {
		if (root == null || !Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Exception ignored) {
				}
			});
		}
	}
}

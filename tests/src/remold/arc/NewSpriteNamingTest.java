package remold.arc;

import arc.struct.Seq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming rules for sprites that are added at runtime (not present in the packed atlas).
 *
 * <p>These cases cover the GL-free half of {@link TextureReloader#registerNewSprite}: the region name
 * must be identical to what a restart would produce, otherwise hot-adding a sprite would silently
 * diverge from cold-start behaviour.</p>
 */
@DisplayName("New Sprite Registration Naming")
public class NewSpriteNamingTest {

	@Test
	@DisplayName("base name strips the extension and truncates at an extra dot")
	public void testSpriteBaseName() {
		assertEquals("copper-wall", TextureReloader.spriteBaseName("copper-wall"));
		assertEquals("icon", TextureReloader.spriteBaseName("icon.9"));
		assertEquals("", TextureReloader.spriteBaseName(null));
		assertEquals("", TextureReloader.spriteBaseName(""));
	}

	@Test
	@DisplayName("sprites-override paths are detected case-insensitively")
	public void testIsOverrideSprite() {
		assertTrue(TextureReloader.isOverrideSprite(Paths.get("/mod/assets/sprites-override/blocks/foo.png")));
		assertTrue(TextureReloader.isOverrideSprite(Paths.get("/mod/assets/Sprites-Override/foo.png")));
		assertFalse(TextureReloader.isOverrideSprite(Paths.get("/mod/assets/sprites/foo.png")));
		assertFalse(TextureReloader.isOverrideSprite(null));
	}

	@Test
	@DisplayName("mod prefix is inferred from a sibling sprite that resolves in the atlas")
	public void testInferModPrefix() {
		Seq<String> mods = Seq.with("mod-a", "mod-b");
		Seq<String> siblings = Seq.with("known");

		assertEquals("mod-b", TextureReloader.inferModPrefix(mods, siblings,
			name -> name.equals("mod-b-known")),
			"the mod whose prefix resolves the sibling sprite owns the directory");

		assertNull(TextureReloader.inferModPrefix(mods, siblings, name -> false),
			"no sibling matches and two mods are enabled: ambiguous, must refuse");

		assertEquals("solo", TextureReloader.inferModPrefix(Seq.with("solo"), Seq.with(), name -> false),
			"a single enabled mod is unambiguous");

		assertNull(TextureReloader.inferModPrefix(Seq.with(), siblings, name -> true),
			"no enabled mods: cannot prefix");
	}

	@Test
	@DisplayName("region name mirrors Mindustry's packing rule and refuses ambiguity")
	public void testResolveRegionName() {
		Seq<String> mods = Seq.with("example-remold-mod");

		assertEquals("example-remold-mod-star",
			TextureReloader.resolveRegionName("star", false, mods, Seq.with(), name -> false),
			"sprites/ must be prefixed with the mod name");

		assertEquals("star",
			TextureReloader.resolveRegionName("star", true, mods, Seq.with(), name -> false),
			"sprites-override/ must not be prefixed");

		assertNull(TextureReloader.resolveRegionName("star", false, Seq.with("a", "b"), Seq.with(), name -> false),
			"ambiguous ownership must refuse instead of guessing");

		assertNull(TextureReloader.resolveRegionName("", false, mods, Seq.with(), name -> false),
			"empty base name must refuse");
	}
}

package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The schema view the gate compares: the loaded class's <b>live reflection</b> field table versus the
 * candidate's field table, in two views — {@code name:desc} (identity, used for logs) and
 * {@code name:desc:static:synthetic} (the verdict).
 *
 * <p><b>Why two views</b>: {@code name:desc} alone cannot see a {@code static} flip or a dropped
 * {@code ACC_SYNTHETIC} on the same field, and the JVM re-lays out instances for those too. The flags
 * view is what the gate compares, so it is pinned here directly.</p>
 *
 * <p><b>Scope</b>: the synthetic-side "union" machinery (tombstone fields, same-name-different-type
 * renaming, retained {@code <init>}) is no longer part of the product path — a member-schema change on a
 * loaded synthetic class is refused outright, so those transforms could never make a redefine legal.
 * They are gone from {@code MixinSyntheticSchema}; the C3 candidate builder in the fixtures keeps its
 * own test-only copy of the constructor union.</p>
 */
class MixinSyntheticSchemaTest {

	/** {@code name:desc} follows the class file order and the exact descriptor spelling. */
	@Test
	void loadedAndCandidateViewsAgreeOnNameAndDescriptor() {
		ClassNode node = MixinSyntheticGateFixtures.nodeOf(HalfInitOld.class);
		assertEquals(List.of("this$0:Ljava/lang/Object;", "val$a:Ljava/lang/Object;"),
			MixinSyntheticSchema.loadedFields(HalfInitOld.class),
			"the loaded view must come from the live class, in declaration order");
		assertEquals(MixinSyntheticSchema.loadedFields(HalfInitOld.class),
			MixinSyntheticSchema.nodeFields(node),
			"the candidate view must spell the same signatures for the same shape");
	}

	/** A {@code static} flip on an identical {@code name:desc} set must show up in the flags view. */
	@Test
	void theFlagsViewSeesAStaticFlipThatTheIdentityViewCannot() {
		Class<?> loaded = HalfInitOld.class;
		ClassNode node = MixinSyntheticGateFixtures.nodeOf(loaded);
		assertEquals(MixinSyntheticSchema.loadedFieldSignatures(loaded),
			MixinSyntheticSchema.nodeFieldSignatures(node),
			"the flags view must agree before anything is mutated");

		FieldNode field = null;
		for (FieldNode candidate : node.fields) {
			if ("val$a".equals(candidate.name)) {
				field = candidate;
			}
		}
		assertTrue(field != null, "precondition: the fixture declares val$a");
		field.access |= org.objectweb.asm.Opcodes.ACC_STATIC;

		assertEquals(MixinSyntheticSchema.loadedFields(loaded), MixinSyntheticSchema.nodeFields(node),
			"the identity view is blind to the flip — that is exactly why the gate cannot use it alone");
		assertNotEquals(MixinSyntheticSchema.loadedFieldSignatures(loaded),
			MixinSyntheticSchema.nodeFieldSignatures(node),
			"the flags view must report the static flip as a schema change");
	}

	/** Dropping {@code ACC_SYNTHETIC} is a schema change too. */
	@Test
	void theFlagsViewSeesADroppedSyntheticFlag() {
		Class<?> loaded = HalfInitOld.class;
		ClassNode node = MixinSyntheticGateFixtures.nodeOf(loaded);
		for (FieldNode field : node.fields) {
			field.access |= org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
		}
		assertNotEquals(MixinSyntheticSchema.loadedFieldSignatures(loaded),
			MixinSyntheticSchema.nodeFieldSignatures(node),
			"marking a field synthetic when the loaded class is not must be visible");
		assertEquals(MixinSyntheticSchema.loadedFields(loaded), MixinSyntheticSchema.nodeFields(node),
			"the identity view again stays identical");
	}

	/** {@code schemaHash} is a stable, shape-sensitive diagnostic fingerprint. */
	@Test
	void schemaHashIsStableForTheSameShapeAndSensitiveToChanges() {
		Class<?> loaded = HalfInitOld.class;
		String first = MixinSyntheticSchema.schemaHash(MixinSyntheticGateFixtures.nodeOf(loaded));
		String second = MixinSyntheticSchema.schemaHash(MixinSyntheticGateFixtures.nodeOf(loaded));
		assertEquals(first, second, "the same shape must hash the same");

		String grown = MixinSyntheticSchema.schemaHash(MixinSyntheticHalfInitFixtures.grownShapeNode());
		assertNotEquals(first, grown, "a grown capture list must hash differently");
		assertFalse(first.isEmpty(), "the hash must be a printable fingerprint");
	}
}

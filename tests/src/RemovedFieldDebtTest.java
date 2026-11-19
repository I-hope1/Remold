import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.vcs.GenerationalFieldLedger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un-closed generational debt must be released when the field is deleted from the new layout
 * ({@code docs/topology/20-generational-field-ledger.md} §2.3).
 *
 * <p><b>What is guarded</b>: the release hangs off the <b>structural</b> signal
 * ({@code diff.changedFields} entry {@code "- name"}) and is keyed exactly by
 * {@code (loader, declaring class, field)}. The earlier version hung off the
 * {@code PENDING}/{@code FieldLedger} retry branch, which an L1/A replay debt never reaches — the
 * row would have leaked for the lifetime of the loader, and the broad field-name match could have
 * released another class's debt.</p>
 */
class RemovedFieldDebtTest {

	private static final String PKG = "rfd1";
	private static final String BASE = PKG + ".Base";
	private static final String BASE_INTERNAL = PKG + "/Base";
	private static final String FIELD = "v";

	private ClassLoader loader;

	@AfterEach
	void cleanUp() {
		GenerationalFieldLedger.INSTANCE.reset();
		RemoldAgent.bytecodeCache.remove(BASE);
	}

	private static String base(int version) {
		if (version == 1) {
			return "package " + PKG + ";\npublic class Base { public int " + FIELD + " = 1; }\n";
		}
		// The field is gone: the only thing left is an unrelated method.
		return "package " + PKG + ";\npublic class Base { public int other() { return 2; } }\n";
	}

	/** A loader that can serve the compiled bytes as resources (offline analysis reads them). */
	private static final class MemoryLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		MemoryLoader(Map<String, byte[]> classes) {
			super(RemovedFieldDebtTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public java.io.InputStream getResourceAsStream(String name) {
			byte[] bytes = classes.get(name.replace('/', '.').replaceAll("\\.class$", ""));
			return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
		}
	}

	/**
	 * Deleting the field releases exactly its debt row; a same-named field on another class in the
	 * same loader keeps its own row.
	 */
	@Test
	void deletingTheFieldReleasesOnlyItsOwnDebt() throws Exception {
		Map<String, String> v1 = new LinkedHashMap<>();
		v1.put(BASE, base(1));
		Map<String, byte[]> v1Classes = LayoutGateAssert.compile(v1);
		Map<String, String> v3 = new LinkedHashMap<>();
		v3.put(BASE, base(3));
		Map<String, byte[]> v3Classes = LayoutGateAssert.compile(v3);
		byte[] before = v1Classes.get(BASE);
		byte[] after = v3Classes.get(BASE);
		assertTrue(new String(before, StandardCharsets.UTF_8).length() > 0, "precondition: v1 bytes");

		loader = new MemoryLoader(v3Classes);
		Class<?> base = loader.loadClass(BASE);
		RemoldAgent.bytecodeCache.put(BASE, after);

		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, BASE_INTERNAL, FIELD);
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, PKG + "/Other", FIELD);
		assertEquals(2, GenerationalFieldLedger.INSTANCE.unclosedCount(loader),
		 "precondition: the debt exists before the removal round");

		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(before, after);
		assertTrue(diff.changedFields.contains("- " + FIELD),
		 "precondition: the diff must carry the structural removal, actual=" + diff.changedFields);

		InitFix.transform(base, after, diff);

		assertFalse(GenerationalFieldLedger.INSTANCE.unclosed(loader).contains(BASE_INTERNAL + "\u0001" + FIELD),
		 "the removed field's debt must be released, ledger="
			 + GenerationalFieldLedger.INSTANCE.unclosed(loader));
		assertTrue(GenerationalFieldLedger.INSTANCE.unclosed(loader).contains(PKG + "/Other\u0001" + FIELD),
		 "another class's same-named debt must survive");
	}
}

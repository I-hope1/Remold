package remold.ref;

import org.junit.jupiter.api.Test;
import java.lang.instrument.ClassDefinition;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.vcs.Changeset;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Changeset.Manifest.candidateMissing} must name only classes whose bytes really are absent
 * ({@code docs/vcs/01-bytecode-changeset-vcs.md} §2.1.2).
 *
 * <p><b>What is guarded</b>: the candidate-to-loader lookup used to be an {@code IdentityHashMap}
 * keyed by loader id {@code String}, so every per-candidate {@code substring} produced a fresh
 * instance, {@code get()} always missed, and <b>every</b> candidate of a successful batch was
 * reported missing — a permanent false alarm that hides the real signal. The bootstrap loader is a
 * second, independent trap: it is addressed by the {@code null} key, and a lookup that
 * short-circuits {@code loader == null} to "no bytes" reports every bootstrap class too.</p>
 *
 * <p>Both cases are asserted against the final committed record, not against the lookup helper: the record is
 * what the commit stores and what the audit reads.</p>
 */
class BatchCommitManifestTest {

	/** The batch view is keyed by the <b>dotted</b> class name, matching {@code RemoldAgent}. */
	private static final String HOST = "probe.ManifestHost";

	private static final class ByteLoader extends ClassLoader {
		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private static byte[] hostBytes(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Positive reading: every candidate has bytes in the batch view, so the manifest must carry an
	 * empty {@code candidateMissing}. Fails on the pre-fix identity lookup (every candidate listed).
	 */
	@Test
	void candidateWithBytesIsNotReportedMissing() {
		ByteLoader loader = new ByteLoader();
		byte[] bytes = hostBytes(HOST.replace('.', '/'));
		Class<?> host = loader.define(HOST, bytes);

		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> byLoader = new LinkedHashMap<>();
		byLoader.put(HOST, bytes);
		view.put(loader, byLoader);

		InitFix.beginBatchBytecodeView(view);
		try {
			Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of(host));
			assertEquals(List.of(), manifest.candidateMissing(),
			 "a candidate whose bytes are in the batch view must not be reported missing");
			assertEquals(List.of("L0:" + HOST), manifest.orderedHosts(),
			 "the same host must still be listed in the deterministic host order");
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}

	/**
	 * Negative control (specificity): drop <b>one</b> of two candidates' bytes and exactly that one
	 * must be reported. Without this the empty list above could equally come from a lookup that
	 * never reports anything.
	 */
	@Test
	void onlyTheClassWithMissingBytesIsReported() {
		ByteLoader loader = new ByteLoader();
		byte[] present = hostBytes(HOST.replace('.', '/'));
		Class<?> host = loader.define(HOST, present);

		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> byLoader = new LinkedHashMap<>();
		byLoader.put(HOST, present);
		byLoader.put("probe.OtherHost", new byte[]{1});
		view.put(loader, byLoader);

		InitFix.beginBatchBytecodeView(view);
		try {
			// The second candidate keeps its registered key but is dropped by the gates, so its bytes
			// never make it into the installable definition set (the real code path in RemoldAgent).
			InitFix.setBatchDefinitions(List.of(new ClassDefinition(host, present)));
			Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of(host));
			assertEquals(List.of("probe.OtherHost"), manifest.candidateMissing(),
			 "exactly the class whose bytes are gone must be named");
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}

	/**
	 * The bootstrap loader is a {@code null} key in the batch view. Its classes are the ones the
	 * name string is easiest to get wrong for, and a {@code loader == null} short-circuit silently
	 * reports all of them.
	 */
	@Test
	void bootstrapLoaderCandidatesResolveNormally() {
		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> bootstrap = new LinkedHashMap<>();
		bootstrap.put("probe.BootHost", hostBytes("probe/BootHost"));
		view.put(null, bootstrap);

		InitFix.beginBatchBytecodeView(view);
		try {
			Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of());
			assertTrue(manifest.candidateMissing().isEmpty(),
			 "bootstrap candidates have bytes in the view, so none may be reported missing"
			 + " (actual=" + manifest.candidateMissing() + ")");
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}
}

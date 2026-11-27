package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.util.Names;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [ANON-MOVED] / [ANON-ORPHAN] counter semantics.
 *
 * <p>Covers the two reporting defects: (1) the aligner must expose the genuinely unpaired new
 * anonymous classes explicitly (a same-name target allocation must not hide them), and (2) the
 * counter must aggregate the whole batch by top-level {@code NestHost} so a move between two
 * nested hosts can meet its candidate.</p>
 */
class AnonMoveReportTest {

	private static final String OUTER_INTERNAL = "move/Outer";
	private static final String OUTER_DOT = "move.Outer";
	private static final String INNER_DOT = "move.Outer$Inner";

	/** Live fixture used only to seed the liveness cache; its name must be a real loaded class. */
	static final class LiveOrphan { }

	@Test
	void alignerExposesUnmatchedNewEvenWhenItGetsTheSameName() {
		String anon = "move/Outer$1";
		byte[] anonBytes = anon(anon, OUTER_INTERNAL, OUTER_INTERNAL, 0);

		AnonClassAligner.Result res = alignSingle(OUTER_DOT,
			Map.of(), Map.of(anon, anonBytes));

		assertTrue(res.alignedAnonClasses.containsKey(anon),
			"precondition: the unpaired new class is allocated its own compiled name: "
				+ res.alignedAnonClasses.keySet());
		assertTrue(res.unmatchedNewClasses.contains(anon),
			"the explicit unmatched set must keep a same-name allocation; actual="
				+ res.unmatchedNewClasses);
		assertTrue(res.orphanOldClasses.isEmpty(), "nothing old was orphaned in this fixture");
	}

	@Test
	void crossNestedHostMoveWithinTheSameTopLevelNestIsReported() {
		String newAnon = "move/Outer$movedOp$1";
		String oldAnon = "move/Outer$Inner$movedOp$1";

		// Real aligner path: the new class is genuinely unpaired (old side empty) and its shape
		// matches the orphan produced under the other nested host. The target slot differs from
		// the compiled name, which is exactly what the old inference would have caught; the
		// explicit unmatched set is what makes the cross-host meeting possible.
		AnonClassAligner.Result outer = alignSingle(OUTER_DOT,
			Map.of(), Map.of(newAnon, anon(newAnon, OUTER_INTERNAL, OUTER_INTERNAL, 0)));
		assertTrue(outer.unmatchedNewClasses.contains(newAnon),
			"aligner must report the unpaired new class: " + outer.unmatchedNewClasses);

		byte[] oldBytes = anon(oldAnon, OUTER_INTERNAL, OUTER_INTERNAL, 0);
		AnonClassAligner.Result inner = orphanResult(oldAnon);

		List<String> infos = captureInfos(() -> BatchHotswapExecutor.reportAnonMoves(List.of(
			new BatchHotswapExecutor.AnonMoveHost(INNER_DOT, inner,
				Map.of(Names.external(oldAnon), oldBytes), Map.of()),
			new BatchHotswapExecutor.AnonMoveHost(OUTER_DOT, outer,
				Map.of(), Map.of(Names.external(newAnon), anon(newAnon, OUTER_INTERNAL, OUTER_INTERNAL, 0))))));

		String moved = firstContaining(infos, "[ANON-MOVED]");
		assertNotNull(moved, "a move between two nested hosts must be reported: " + infos);
		assertTrue(moved.contains("host=" + Names.internal(INNER_DOT)), moved);
		assertTrue(moved.contains("orphan=" + oldAnon), moved);
		assertTrue(moved.contains("candidate=" + newAnon), moved);
		assertTrue(moved.contains("scope=cross-host"), moved);
		assertTrue(moved.contains("candidateHost=" + Names.internal(OUTER_DOT)), moved);
		assertTrue(infos.stream().noneMatch(l -> l.startsWith("[ANON-ORPHAN]")),
			"the matched orphan must not also be reported as unmatched: " + infos);
	}

	@Test
	void differentTopLevelNestHostsNeverMatchShapes() {
		String orphan = "move/ClassA$1";
		String candidate = "move/ClassB$1";

		AnonClassAligner.Result a = orphanResult(orphan);
		AnonClassAligner.Result b = new AnonClassAligner.Result(null, Map.of(), Map.of(),
			Set.of(), new AnonClassAligner.AlignmentStats(), Set.of(candidate));

		List<String> infos = captureInfos(() -> BatchHotswapExecutor.reportAnonMoves(List.of(
			new BatchHotswapExecutor.AnonMoveHost("move.ClassA", a,
				Map.of("move.ClassA$1", anon(orphan, "move/ClassA", "move/ClassA", 0)), Map.of()),
			new BatchHotswapExecutor.AnonMoveHost("move.ClassB", b,
				Map.of(), Map.of("move.ClassB$1", anon(candidate, "move/ClassB", "move/ClassB", 0))))));

		assertTrue(infos.stream().noneMatch(l -> l.startsWith("[ANON-MOVED]")),
			"anonymous classes from different top-level nest hosts must never match: " + infos);
		String orphanLine = firstContaining(infos, "[ANON-ORPHAN]");
		assertNotNull(orphanLine, "the unmatched orphan still needs its own reading: " + infos);
		assertTrue(orphanLine.contains("orphan=" + orphan), orphanLine);
	}

	@Test
	void sameHostCaptureGrowthStillReportsTheGate() {
		String dot = LiveOrphan.class.getName();
		String internal = Names.internal(dot);
		String candidate = "remold/AnonMoveReportTest$LiveNew";
		String nest = "remold/AnonMoveReportTest";

		RemoldAgent.loadedClassesMap.put(dot, LiveOrphan.class);
		RemoldAgent.LIVE_INSTANCE_CACHE.put(dot, true);
		try {
			AnonClassAligner.Result res = new AnonClassAligner.Result(null, Map.of(), Map.of(),
				Set.of(internal), new AnonClassAligner.AlignmentStats(), Set.of(candidate));
			List<String> infos = captureInfos(() -> BatchHotswapExecutor.reportAnonMoves(List.of(
				new BatchHotswapExecutor.AnonMoveHost(dot, res,
					Map.of(dot, anon(internal, nest, nest, 0)),
					Map.of(Names.external(candidate), anon(candidate, nest, nest, 1))))));

			String moved = firstContaining(infos, "[ANON-MOVED]");
			assertNotNull(moved, "same-host capture growth must stay a MOVED reading: " + infos);
			assertTrue(moved.contains("scope=same-host"), moved);
			assertTrue(moved.contains("live=1"), moved);
			assertTrue(moved.contains("gate=REJECT(capture-grew)"), moved);
		} finally {
			RemoldAgent.loadedClassesMap.remove(dot);
			RemoldAgent.LIVE_INSTANCE_CACHE.remove(dot);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static AnonClassAligner.Result alignSingle(String hostDot, Map<String, byte[]> oldAnon,
	                                                   Map<String, byte[]> newAnon) {
		return AnonClassAligner.align(hostDot, null, oldAnon, newAnon,
			name -> oldAnon.get(Names.external(name)),
			name -> newAnon.get(Names.external(name)));
	}

	private static AnonClassAligner.Result orphanResult(String orphanInternal) {
		return new AnonClassAligner.Result(null, Map.of(), Map.of(),
			Set.of(orphanInternal), new AnonClassAligner.AlignmentStats(), Set.of());
	}

	private static byte[] anon(String name, String nestHost, String enclosing, int syntheticFields) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, "java/lang/Object", null);
		cw.visitNestHost(nestHost);
		cw.visitOuterClass(enclosing, "movedOp", "()V");
		cw.visitInnerClass(name, null, null,
			Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
		for (int i = 0; i < syntheticFields; i++) {
			cw.visitField(Opcodes.ACC_SYNTHETIC, "val$x" + i, "Ljava/lang/Object;", null, null).visitEnd();
		}
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<String> captureInfos(Runnable action) {
		List<String> infos = new ArrayList<>();
		RemoldAgent.Logger saved = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { }
			@Override public void info(String msg) { infos.add(msg); }
			@Override public void warn(String msg) { infos.add("WARN " + msg); }
			@Override public void error(String msg) { infos.add("ERROR " + msg); }
			@Override public void error(String msg, Throwable t) { infos.add("ERROR " + msg); }
		};
		try {
			action.run();
		} finally {
			RemoldAgent.logger = saved;
		}
		return infos;
	}

	private static String firstContaining(List<String> lines, String needle) {
		for (String line : lines) {
			if (line != null && line.contains(needle)) return line;
		}
		return null;
	}
}

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ref.InitializerDrift;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * L0 cost probe: N Blocks.java-style anonymous subclasses whose &lt;init&gt; writes an
 * inherited field. Measures InitializerDrift.detect per class (min/median/max) and the
 * whole batch. Generated classes, not a compiled Blocks.java; no JIT/GC conclusion is
 * drawn beyond this probe's own distribution.
 */
public class InitDriftPerfProbe {

	static final int N = Integer.getInteger("initdrift.n", 300);
	static final int WARMUP = Integer.getInteger("initdrift.warmup", 50);
	static final int ROUNDS = Integer.getInteger("initdrift.rounds", 7);

	public static void main(String[] args) {
		List<byte[]> oldBytes = new ArrayList<>();
		List<byte[]> newBytes = new ArrayList<>();
		for (int i = 0; i < N; i++) {
			oldBytes.add(anon("perf/Anon" + i, 90));
			newBytes.add(anon("perf/Anon" + i, 100));
		}

		for (int i = 0; i < WARMUP; i++) {
			InitializerDrift.detect(oldBytes.get(i % N), newBytes.get(i % N));
		}

		double[] perClassMs = new double[N * ROUNDS];
		double[] batchMs = new double[ROUNDS];
		int k = 0;
		for (int r = 0; r < ROUNDS; r++) {
			long batchStart = System.nanoTime();
			for (int i = 0; i < N; i++) {
				long t0 = System.nanoTime();
				List<String> changed = InitializerDrift.detect(oldBytes.get(i), newBytes.get(i));
				long dt = System.nanoTime() - t0;
				if (changed.size() != 1 || !"health".equals(changed.get(0))) {
					throw new AssertionError("detector missed health at " + i + ": " + changed);
				}
				perClassMs[k++] = dt / 1_000_000.0;
			}
			batchMs[r] = (System.nanoTime() - batchStart) / 1_000_000.0;
		}

		Arrays.sort(perClassMs);
		Arrays.sort(batchMs);
		System.out.println("L0 InitDriftPerfProbe: N=" + N + " warmup=" + WARMUP + " rounds=" + ROUNDS);
		System.out.printf("per-class ms: min=%.4f median=%.4f max=%.4f%n",
			perClassMs[0], perClassMs[perClassMs.length / 2], perClassMs[perClassMs.length - 1]);
		System.out.printf("batch(%d) ms: min=%.4f median=%.4f max=%.4f%n",
			N, batchMs[0], batchMs[batchMs.length / 2], batchMs[batchMs.length - 1]);
	}

	static byte[] anon(String name, int value) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
			name, null, "perf/Parent", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "perf/Parent", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitIntInsn(Opcodes.BIPUSH, value);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "perf/Parent", "health", "I");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}

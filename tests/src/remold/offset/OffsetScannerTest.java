package remold.offset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import remold.offset.OffsetScanner.Binding;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class OffsetScannerTest {

	private static final Map<String, byte[]> GEN = new HashMap<>();

	private static byte[] load(String name) {
		byte[] g = GEN.get(name);
		if (g != null) return g;
		try (InputStream in = OffsetScannerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
			if (in != null) return in.readAllBytes();
		} catch (Exception ignored) { }
		try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
			if (in != null) return in.readAllBytes();
		} catch (Exception ignored) { }
		try {
			Class<?> c = Class.forName(name.replace('/', '.'));
			try (InputStream in = c.getModule().getResourceAsStream(name + ".class")) {
				if (in != null) return in.readAllBytes();
			}
		} catch (Exception ignored) { }
		return null;
	}

	private static String fmt(Binding b) {
		return b.kind() + " " + b.sink() + " " + b.targetClass() + "." + b.targetField() + " " + b.conf();
	}

	/** ASM 生成：static final VarHandle H = ldc condy(fieldVarHandle, remold/offset/cases/Foo, "x", int) */
	private static byte[] genCondy() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "remold/offset/cases/CondyVH", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "H", "Ljava/lang/invoke/VarHandle;", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		mv.visitCode();
		Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "fieldVarHandle",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;", false);
		ConstantDynamic cd = new ConstantDynamic("vh", "Ljava/lang/invoke/VarHandle;", bsm,
				Type.getObjectType("remold/offset/cases/Foo"), "x", Type.INT_TYPE);
		mv.visitLdcInsn(cd);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, "remold/offset/cases/CondyVH", "H", "Ljava/lang/invoke/VarHandle;");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	@DisplayName("合成样例全覆盖测试（21个样例类 + Condy VarHandle）")
	public void testSyntheticCases() {
		GEN.put("remold/offset/cases/CondyVH", genCondy());

		Map<String, Set<String>> exp = new LinkedHashMap<>();
		exp.put("Direct", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.x EXACT"));
		exp.put("ViaLocal", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.y EXACT"));
		exp.put("ViaHelper", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.y EXACT"));
		exp.put("ViaHelperName", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.x EXACT"));
		exp.put("ViaFieldParam", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.ref EXACT"));
		exp.put("ViaField0", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.cnt EXACT"));
		exp.put("DoPriv", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.ref EXACT"));
		exp.put("DoPrivLambda", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.cnt EXACT"));
		exp.put("Concat", Set.of("INSTANCE_OFFSET INSTANCE_FIELD remold/offset/cases/Foo.null WILDCARD_FIELD"));
		exp.put("Loop", Set.of("INSTANCE_OFFSET ESCAPED remold/offset/cases/Foo.null WILDCARD_FIELD"));
		exp.put("Immediate", Set.of());
		exp.put("Updaters", Set.of("UPDATER STATIC_FIELD remold/offset/cases/Foo.cnt EXACT",
		                           "UPDATER STATIC_FIELD remold/offset/cases/Foo.ref EXACT"));
		exp.put("VH", Set.of("VARHANDLE STATIC_FIELD remold/offset/cases/Foo.x EXACT"));
		exp.put("DynHolder", Set.of("INSTANCE_OFFSET INSTANCE_FIELD null.x UNRESOLVED_CLASS"));
		exp.put("MergeF", Set.of("INSTANCE_OFFSET INSTANCE_FIELD remold/offset/cases/Foo.null WILDCARD_FIELD"));
		exp.put("StaticField", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.x EXACT"));
		exp.put("Boxed", Set.of("INSTANCE_OFFSET ESCAPED remold/offset/cases/Foo.x EXACT"));
		exp.put("Derived", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.x EXACT"));
		exp.put("Logged", Set.of("INSTANCE_OFFSET STATIC_FIELD remold/offset/cases/Foo.y EXACT"));
		exp.put("Plain", Set.of());
		exp.put("CondyVH", Set.of("VARHANDLE STATIC_FIELD remold/offset/cases/Foo.x EXACT"));

		// 故意把依赖 helper 的类排在 Util 之前，验证 producer 多轮收敛
		List<String> names = new ArrayList<>();
		for (String k : exp.keySet()) names.add("remold/offset/cases/" + k);
		names.add("remold/offset/cases/Util");
		names.add("remold/offset/cases/Uns");
		names.add("remold/offset/cases/Foo");

		OffsetScanner sc = new OffsetScanner(OffsetScannerTest::load);
		OffsetScanner.Report r = sc.scanAll(names);

		for (var e : exp.entrySet()) {
			String holder = "remold/offset/cases/" + e.getKey();
			Set<String> got = r.bindings.stream()
					.filter(b -> b.holderClass().equals(holder) || b.holderClass().startsWith(holder + "$"))
					.map(OffsetScannerTest::fmt).collect(Collectors.toCollection(TreeSet::new));
			assertEquals(new TreeSet<>(e.getValue()), got, "Mismatch in case " + e.getKey());
		}
		assertTrue(r.analysisFailed.isEmpty(), "analysisFailed must be empty: " + r.analysisFailed);
		assertTrue(r.unreadable.isEmpty(), "unreadable must be empty: " + r.unreadable);
	}

	@Test
	@DisplayName("负向对照：ViaHelper 单独扫描且 Util 不在集合中时，不应产出任何绑定")
	public void testNegativeControlWithoutProducer() {
		OffsetScanner sc = new OffsetScanner(OffsetScannerTest::load);
		OffsetScanner.Report r = sc.scanAll(List.of("remold/offset/cases/ViaHelper"));
		assertEquals(0, r.bindings.size(), "Without producer Util in scope, no bindings should be produced");
		assertEquals(1, r.skippedByPrefilter, "ViaHelper should be skipped by prefilter when producer is unknown");
	}

	@Test
	@DisplayName("真实 JDK 并发核心类扫描识别（ConcurrentHashMap, AQS, Striped64, Updater）")
	public void testRealJdkClasses() {
		List<String> jdk = List.of(
			"java/util/concurrent/ConcurrentHashMap",
			"java/util/concurrent/locks/AbstractQueuedSynchronizer",
			"java/util/concurrent/atomic/AtomicBoolean",
			"java/util/concurrent/atomic/AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl",
			"java/util/concurrent/ForkJoinPool",
			"java/util/concurrent/atomic/Striped64"
		);
		OffsetScanner sc = new OffsetScanner(OffsetScannerTest::load);
		OffsetScanner.Report r = sc.scanAll(jdk);

		// Assert key exact bindings are discovered
		Set<String> targetFields = r.bindings.stream()
				.map(Binding::targetField)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());

		assertTrue(targetFields.contains("sizeCtl"), "ConcurrentHashMap.sizeCtl must be detected");
		assertTrue(targetFields.contains("state"), "AQS.state must be detected");
		assertTrue(targetFields.contains("head"), "AQS.head must be detected");
		assertTrue(targetFields.contains("tail"), "AQS.tail must be detected");

		// Dynamic holder check: AtomicIntegerFieldUpdaterImpl has unresolved targetClass
		boolean hasDynamicHolder = r.bindings.stream()
				.anyMatch(b -> b.holderClass().contains("AtomicIntegerFieldUpdater")
						&& b.conf() == OffsetScanner.Confidence.UNRESOLVED_CLASS);
		assertTrue(hasDynamicHolder, "AtomicIntegerFieldUpdaterImpl must produce UNRESOLVED_CLASS dynamic holder");
	}
}

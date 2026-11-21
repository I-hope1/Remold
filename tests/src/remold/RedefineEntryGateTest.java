package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redefine 入口的 class 文件合法性门禁（{@code docs/status.md} §5 风险 13）。
 *
 * <p>每条负向用例都落在<b>终局</b>上：拒绝原因非空、{@code redefineClasses} 调用次数为 0、
 * 该类进了 skip 台账、并且 warn 里点了名。正向对照（合法字节）证明门禁不是"一律拦"，
 * 同时钉住"送进 JVM 的字节就是校验过的那一份引用"。</p>
 */
class RedefineEntryGateTest {

	private static final String INTERNAL = "probe/GateTarget";
	private static final String DOT = "probe.GateTarget";
	private static final String OTHER_INTERNAL = "probe/OtherTarget";

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final Instrumentation originalInstrumentation = RemoldAgent.getInstrumentation();
	private final RecordingLogger recorder = new RecordingLogger();
	private final RecordingInstrumentation instrumentation = new RecordingInstrumentation();

	@BeforeEach
	void install() {
		RemoldAgent.logger = this.recorder;
		RemoldAgent.swapInstrumentationForTest(this.instrumentation.proxy);
	}

	@AfterEach
	void restore() {
		RemoldAgent.swapInstrumentationForTest(this.originalInstrumentation);
		RemoldAgent.logger = this.originalLogger;
		RemoldAgent.bytecodeCache.remove(DOT);
		RemoldAgent.bytecodeCache.remove("probe.OtherTarget");
	}

	// ------------------------------------------------------------ 负向用例（四项各一条）

	/** 畸形魔数：{@code 0xDEADBEEF} 的字节绝不能进 redefine。 */
	@Test
	void badMagicIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(INTERNAL);
		bytes[0] = (byte) 0xDE;
		bytes[1] = (byte) 0xAD;
		bytes[2] = (byte) 0xBE;
		bytes[3] = (byte) 0xEF;
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "magic");
	}

	/**
	 * 常量池被截断：魔数与 major 都合法、长度也远超 8 字节，只有逐项走常量池才能发现读到末端。
	 *
	 * <p>这条正是"不要只判 {@code length >= N}"的守卫：任何长度代理判据都会放它过去。</p>
	 */
	@Test
	void truncatedConstantPoolIsRejectedBeforeRedefine() {
		byte[] whole = validBytes(INTERNAL);
		byte[] truncated = Arrays.copyOf(whole, 16);
		Class<?> target = define(INTERNAL, whole);

		assertTrue(truncated.length >= 8,
		 "precondition: the sample is longer than the plain header, so a length check alone would pass it");
		assertRejectedAndNeverRedefined(target, truncated, "truncated");
	}

	/** 未知 class major：显式拒绝，而不是交给 JVM 去猜。 */
	@Test
	void unknownClassMajorIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(INTERNAL);
		bytes[6] = (byte) 0x03;
		bytes[7] = (byte) 0xE8; // 1000
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "major");
	}

	/** 二进制名与目标类不一致：合法 class 文件也必须拒绝。 */
	@Test
	void binaryNameMismatchIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(OTHER_INTERNAL);
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "does not match");
	}

	/**
	 * 常量池完整、{@code this_class} 之后被截断：只有真解析一遍结构才能发现。
	 *
	 * <p>这份字节的魔数、{@code major}、整张常量池与 {@code this_class} 全部合法——廉价前置走查会
	 * 放它过去（截断点正好落在 {@code access_flags + this_class} 之后）。{@code super_class} 起的所有
	 * 结构都缺失，必须由 ASM 解析那一步拒掉，否则畸形 {@code ClassDefinition} 会直接进入
	 * {@code redefineClasses}（JBR 上就是 VM 崩溃，见 {@code docs/status.md} §5 风险 13）。</p>
	 */
	@Test
	void truncatedAfterThisClassIsRejectedBeforeRedefine() {
		byte[] whole = validBytes(INTERNAL);
		int beforeSuperClass = endOfConstantPool(whole) + 4;   // access_flags(2) + this_class(2)
		byte[] truncated = Arrays.copyOf(whole, beforeSuperClass);
		Class<?> target = define(INTERNAL, whole);

		assertTrue(beforeSuperClass < whole.length - 4,
		 "precondition: the sample really loses the tail (kept " + beforeSuperClass + " of " + whole.length + ")");
		String problem = RemoldAgent.definitionProblem(target, truncated);
		assertNotNull(problem,
		 "a class file truncated right after this_class must be refused: constant pool and this_class alone"
				+ " do not prove the structure is complete");
		assertTrue(problem.contains("ASM"),
		 "the refusal must come from the structural pass, not from the cheap prefix walk: " + problem);
		assertRejectedAndNeverRedefined(target, truncated, "ASM");
	}

	/**
	 * 类名含增补字符（{@code U+1F600}）的合法字节必须放行。
	 *
	 * <p>常量池的 {@code Utf8} 是 modified UTF-8：该字符在池里是代理对拆开的两个三字节序列（CESU-8），
	 * 标准 UTF-8 解码会解成替换字符，于是"名字不一致"的误判把合法字节拒掉。名字比较改按字节做之后，
	 * 这条用例放行；下一节的"名字确实不符"仍然被拒（否则就成了"一律放行"的假修复）。</p>
	 */
	@Test
	void supplementaryCharacterClassNameIsAccepted() {
		String internal = "probe/GateTarget\uD83D\uDE00";
		String dot = "probe.GateTarget\uD83D\uDE00";
		byte[] bytes = validBytes(internal);
		Class<?> target = define(internal, bytes);

		assertEquals(dot, target.getName(), "precondition: the JVM keeps the supplementary character in the name");
		assertNull(RemoldAgent.definitionProblem(target, bytes),
		 "a legal class name carrying a supplementary character must pass the gate: "
				+ RemoldAgent.definitionProblem(target, bytes));

		List<ClassDefinition> safe = RemoldAgent.gateDefinitions(
		 List.of(new ClassDefinition(target, bytes)), new RemoldAgent.RoundSkips());
		assertEquals(1, safe.size(), "the gate must not drop a legal class whose name needs modified UTF-8");
	}

	/** 名字确实不符（两个名字都含增补字符）仍被拒：字节比较不是"一律放行"。 */
	@Test
	void supplementaryCharacterNameMismatchIsStillRejected() {
		String internal = "probe/GateTarget\uD83D\uDE00";
		String otherInternal = "probe/GateTarget\uD83D\uDE01";
		Class<?> target = define(internal, validBytes(internal));

		String problem = RemoldAgent.definitionProblem(target, validBytes(otherInternal));
		assertNotNull(problem, "a different supplementary-character name must be refused");
		assertTrue(problem.contains("does not match"), "the refusal must name the mismatch: " + problem);
	}

	// ------------------------------------------------------------ 正向对照

	/**
	 * 合法字节照常进 redefine：门禁不是"一律拦"。
	 *
	 * <p>同时断言送进 {@code redefineClasses} 的字节数组<b>就是</b>构造 {@link ClassDefinition} 时
	 * 那一份引用——校验与送 JVM 之间没有重读磁盘的窗口（风险 1 的 TOCTOU 半写）。</p>
	 */
	@Test
	void validDefinitionReachesRedefineWithTheSameBytesInstance() {
		byte[] bytes = validBytes(INTERNAL);
		Class<?> target = define(INTERNAL, bytes);
		ClassDefinition definition = new ClassDefinition(target, bytes);

		RemoldAgent.RedefineOutcome outcome = RemoldAgent.applyRedefinitions(
		 List.of(definition), List.of(), new RemoldAgent.RoundSkips());

		assertEquals(1, this.instrumentation.redefinitions.size(),
		 "a well-formed definition must reach redefineClasses (the gate cannot be a blanket block)");
		assertSame(definition, this.instrumentation.redefinitions.get(0)[0],
		 "the very same ClassDefinition instance must be handed to the JVM");
		assertSame(bytes, ((ClassDefinition) this.instrumentation.redefinitions.get(0)[0]).getDefinitionClassFile(),
		 "the bytes handed to the JVM must be the same array reference that was validated (no re-read)");
		assertTrue(outcome.successful().contains(DOT), "the accepted class is reported as redefined: " + outcome);
	}

	/** 门禁本身不复制、不替换字节：放行的就是刚才那一个对象。 */
	@Test
	void gateHandsOverTheSameInstance() {
		byte[] bytes = validBytes(INTERNAL);
		Class<?> target = define(INTERNAL, bytes);
		ClassDefinition definition = new ClassDefinition(target, bytes);

		List<ClassDefinition> safe = RemoldAgent.gateDefinitions(
		 List.of(definition), new RemoldAgent.RoundSkips());

		assertEquals(1, safe.size());
		assertSame(definition, safe.get(0), "the gate must not rebuild the definition");
		assertSame(bytes, safe.get(0).getDefinitionClassFile(), "the gate must not copy the bytes");
	}

	// ------------------------------------------------------------ major 上限的推导

	/**
	 * 上限按运行期 {@code Runtime.version().feature() + 44} 推导，不是钉死的某个常量。
	 *
	 * <p>边界两侧各断言一次：{@code MAX} 放行、{@code MAX + 1} 拒绝。这样"改成恒通过"与
	 * "钉死成 65"两种退化都会在这里露出。</p>
	 */
	@Test
	void classMajorCeilingIsDerivedFromTheRunningJvm() {
		assertEquals(Runtime.version().feature() + 44, RemoldAgent.MAX_CLASS_MAJOR,
		 "the ceiling must be derived from the running JVM, not hard-coded");

		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		byte[] atCeiling = validBytes(INTERNAL);
		atCeiling[6] = (byte) ((RemoldAgent.MAX_CLASS_MAJOR >> 8) & 0xFF);
		atCeiling[7] = (byte) (RemoldAgent.MAX_CLASS_MAJOR & 0xFF);
		assertNull(RemoldAgent.definitionProblem(target, atCeiling),
		 "a class file at the running JVM's ceiling must be accepted");

		byte[] aboveCeiling = validBytes(INTERNAL);
		int tooNew = RemoldAgent.MAX_CLASS_MAJOR + 1;
		aboveCeiling[6] = (byte) ((tooNew >> 8) & 0xFF);
		aboveCeiling[7] = (byte) (tooNew & 0xFF);
		String problem = RemoldAgent.definitionProblem(target, aboveCeiling);
		assertNotNull(problem, "a class file newer than the running JVM must be refused");
		assertTrue(problem.contains("major"), "the refusal must name the version problem: " + problem);
	}

	// ------------------------------------------------------------ 现代常量池 tag（不得误伤合法新版本产物）

	/**
	 * {@code 17 Dynamic} / {@code 18 InvokeDynamic}：真实可加载的现代常量池产物必须放行。
	 *
	 * <p>字节由 ASM 现造：一个 {@code LDC} 动态常量（{@code CONSTANT_Dynamic}）+ 一个 lambda 调用点
	 * （{@code CONSTANT_InvokeDynamic}）。这两项在旧走查器里漏一个 tag，就会把整类当成"截断"拒掉——
	 * 这类误伤比放过畸形字节更难发现，因为日志里只说"unparseable"。</p>
	 */
	@Test
	void dynamicAndInvokeDynamicConstantPoolEntriesAreAccepted() throws Exception {
		byte[] bytes = modernDynamicBytes(INTERNAL);
		Class<?> target = define(INTERNAL, bytes);
		ClassDefinition definition = new ClassDefinition(target, bytes);

		assertNull(RemoldAgent.definitionProblem(target, bytes),
		 "a legal class file carrying CONSTANT_Dynamic/CONSTANT_InvokeDynamic must pass the gate");
		List<ClassDefinition> safe = RemoldAgent.gateDefinitions(
		 List.of(definition), new RemoldAgent.RoundSkips());
		assertEquals(1, safe.size(), "the gate must not drop a legal modern class file");
	}

	/**
	 * {@code 19 Module} / {@code 20 Package}：真实 module-info 常量池必须整池走完。
	 *
	 * <p>这里断言的不是"module-info 能过门禁"（它的类名本来就不匹配目标类），而是<b>拒绝原因必须是
	 * 名字不匹配</b>——只要出现 {@code unknown constant pool tag 19/20} 或 {@code truncated}，就说明
	 * tag 遍历漏了条目，"合法新版产物被误伤"。</p>
	 */
	@Test
	void moduleAndPackageConstantPoolEntriesAreWalkedToTheEnd() {
		byte[] moduleBytes = moduleInfoBytes("probe/gate");
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		String problem = RemoldAgent.definitionProblem(target, moduleBytes);
		assertNotNull(problem, "a module-info class name never matches a normal target class");
		assertTrue(problem.contains("does not match"),
		 "the only reason may be the binary-name mismatch (tags 19/20 must be walked, not rejected): " + problem);
		assertFalse(problem.contains("unknown constant pool tag"),
		 "tags 19 (Module) and 20 (Package) must be covered by the walker: " + problem);
		assertFalse(problem.contains("truncated"),
		 "the walker must reach the end of a module class file's constant pool: " + problem);
	}

	/**
	 * 手工构造：一个 {@code this_class} 匹配目标类、常量池同时含 {@code 19 Module} / {@code 20 Package} /
	 * {@code 17 Dynamic} / {@code 18 InvokeDynamic} 的 class 文件，门禁必须<b>放行</b>。
	 *
	 * <p>这些条目对"入口门禁"只要求结构可读：门禁是进 JVM 之前的结构闸，不是字节码校验器。JVMS 限定
	 * 19/20 只出现在 module 类里，所以这份字节不会真的被 {@code defineClass}；断言点正是"不要因为见到
	 * 新 tag 就拒"，这正是误伤合法新版本产物的那类退化。</p>
	 */
	@Test
	void handBuiltModernConstantPoolWithMatchingNameIsAccepted() throws Exception {
		byte[] bytes = taggedConstantPoolBytes(INTERNAL);
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertNull(RemoldAgent.definitionProblem(target, bytes),
		 "a matching-name class file whose constant pool uses tags 19/20/17/18 must pass: "
				+ RemoldAgent.definitionProblem(target, bytes));
	}

	/**
	 * 合法现代 class 字节：<b>真实可加载</b>的 {@code CONSTANT_Dynamic} + {@code CONSTANT_InvokeDynamic}。
	 */
	private static byte[] modernDynamicBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();

		// 常量池 tag 17：LDC 一个动态常量，引导方法就在本类里。
		MethodVisitor dynamic = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dynamicValue",
		 "()Ljava/lang/Object;", null, null);
		dynamic.visitCode();
		dynamic.visitLdcInsn(new ConstantDynamic("cd", "Ljava/lang/Object;", new Handle(
		 Opcodes.H_INVOKESTATIC, internalName, "bsm",
		 "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
		 false)));
		dynamic.visitInsn(Opcodes.ARETURN);
		dynamic.visitMaxs(1, 0);
		dynamic.visitEnd();

		MethodVisitor bsm = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bsm",
		 "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
		 null, null);
		bsm.visitCode();
		bsm.visitInsn(Opcodes.ACONST_NULL);
		bsm.visitInsn(Opcodes.ARETURN);
		bsm.visitMaxs(1, 3);
		bsm.visitEnd();

		// 常量池 tag 18：一个真实的 LambdaMetafactory 调用点。
		MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "runnable",
		 "()Ljava/lang/Runnable;", null, null);
		lambda.visitCode();
		lambda.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", new Handle(
		 Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
		 "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
			 + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
			 + "Ljava/lang/invoke/CallSite;", false),
		 org.objectweb.asm.Type.getMethodType("()V"),
		 new Handle(Opcodes.H_INVOKESTATIC, internalName, "runBody", "()V", false),
		 org.objectweb.asm.Type.getMethodType("()V"));
		lambda.visitInsn(Opcodes.ARETURN);
		lambda.visitMaxs(1, 0);
		lambda.visitEnd();

		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "runBody", "()V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** 合法 module-info（常量池含 {@code 19 Module} + {@code 20 Package}）。 */
	private static byte[] moduleInfoBytes(String moduleName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
		org.objectweb.asm.ModuleVisitor mv = cw.visitModule(moduleName, 0, null);
		mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
		mv.visitPackage(moduleName + "/pkg");
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 手工常量池：{@code this_class} 指向目标类名，池内含 17/18/19/20。
	 *
	 * <p>只构造到"常量池 + 类头"为止（fields/methods/attributes 全 0）：门禁的契约是"能读完常量池、
	 * 类头可读、名字匹配"，这份字节正好逐项压到它。</p>
	 */
	private static byte[] taggedConstantPoolBytes(String internalName) throws Exception {
		Pool pool = new Pool();
		int thisName = pool.utf8(internalName);
		int thisClass = pool.clazz(thisName);
		int objectName = pool.utf8("java/lang/Object");
		int objectClass = pool.clazz(objectName);
		pool.integer(7);
		pool.afloat(1.5f);
		pool.along(42L);
		pool.adouble(2.5d);
		int utf8Field = pool.utf8("field");
		int utf8Desc = pool.utf8("Ljava/lang/Object;");
		int nat = pool.nameAndType(utf8Field, utf8Desc);
		int fieldref = pool.ref(9, objectClass, nat);
		pool.ref(10, objectClass, nat);
		pool.ref(11, objectClass, nat);
		int stringEntry = pool.string(utf8Field);
		pool.methodType(utf8Desc);
		pool.methodHandle(Opcodes.H_GETSTATIC, fieldref);
		pool.dynamic(17, 0, nat);
		pool.dynamic(18, 0, nat);
		pool.module(pool.utf8("probe.modular"));
		pool.pkg(pool.utf8("probe/pkg"));

		// CONSTANT_Dynamic / InvokeDynamic 都必须有引导方法表；缺了它这份字节就不是可加载的类文件，
		// 第二步的 ASM 解析会（正确地）拒掉。这里补一张合法的 BootstrapMethods，让样本仍然"可放行"。
		int bootstrapMethodsName = pool.utf8("BootstrapMethods");
		int methodHandle = pool.methodHandle(Opcodes.H_GETSTATIC, fieldref);

		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream data = new java.io.DataOutputStream(out);
		data.writeInt(0xCAFEBABE);
		data.writeShort(0);
		data.writeShort(61);                 // JDK 17 产物
		data.writeShort(pool.size());
		data.write(pool.bytes());
		data.writeShort(Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER);
		data.writeShort(thisClass);
		data.writeShort(objectClass);
		data.writeShort(0);                  // interfaces
		data.writeShort(0);                  // fields
		data.writeShort(0);                  // methods
		data.writeShort(1);                  // attributes: BootstrapMethods
		data.writeShort(bootstrapMethodsName);
		data.writeInt(2 + 2 + 2);            // num_bootstrap_methods + one (ref, argCount) pair
		data.writeShort(1);                  // num_bootstrap_methods
		data.writeShort(methodHandle);       // bootstrap_method_ref
		data.writeShort(0);                  // num_bootstrap_arguments
		data.flush();
		return out.toByteArray();
	}

	/** 极简 class 常量池写入器：只为构造"含全部现代 tag"的字节。 */
	private static final class Pool {
		private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		private final java.io.DataOutputStream out = new java.io.DataOutputStream(this.bytes);
		private int next = 1;

		int size() { return this.next; }
		byte[] bytes() { return this.bytes.toByteArray(); }

		int utf8(String value) throws java.io.IOException {
			byte[] payload = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			this.out.writeByte(1);
			this.out.writeShort(payload.length);
			this.out.write(payload);
			return this.next++;
		}

		int integer(int value) throws java.io.IOException {
			this.out.writeByte(3);
			this.out.writeInt(value);
			return this.next++;
		}

		int afloat(float value) throws java.io.IOException {
			this.out.writeByte(4);
			this.out.writeFloat(value);
			return this.next++;
		}

		/** {@code Long} 占两个常量池槽：返回首个槽的索引。 */
		int along(long value) throws java.io.IOException {
			this.out.writeByte(5);
			this.out.writeLong(value);
			int at = this.next;
			this.next += 2;
			return at;
		}

		/** {@code Double} 占两个常量池槽。 */
		int adouble(double value) throws java.io.IOException {
			this.out.writeByte(6);
			this.out.writeDouble(value);
			int at = this.next;
			this.next += 2;
			return at;
		}

		int clazz(int nameIndex) throws java.io.IOException {
			this.out.writeByte(7);
			this.out.writeShort(nameIndex);
			return this.next++;
		}

		int string(int utf8Index) throws java.io.IOException {
			this.out.writeByte(8);
			this.out.writeShort(utf8Index);
			return this.next++;
		}

		int ref(int tag, int classIndex, int nameAndTypeIndex) throws java.io.IOException {
			this.out.writeByte(tag);
			this.out.writeShort(classIndex);
			this.out.writeShort(nameAndTypeIndex);
			return this.next++;
		}

		int nameAndType(int nameIndex, int descriptorIndex) throws java.io.IOException {
			this.out.writeByte(12);
			this.out.writeShort(nameIndex);
			this.out.writeShort(descriptorIndex);
			return this.next++;
		}

		int methodHandle(int kind, int referenceIndex) throws java.io.IOException {
			this.out.writeByte(15);
			this.out.writeByte(kind);
			this.out.writeShort(referenceIndex);
			return this.next++;
		}

		int methodType(int descriptorIndex) throws java.io.IOException {
			this.out.writeByte(16);
			this.out.writeShort(descriptorIndex);
			return this.next++;
		}

		int dynamic(int tag, int bootstrapIndex, int nameAndTypeIndex) throws java.io.IOException {
			this.out.writeByte(tag);
			this.out.writeShort(bootstrapIndex);
			this.out.writeShort(nameAndTypeIndex);
			return this.next++;
		}

		int module(int nameIndex) throws java.io.IOException {
			this.out.writeByte(19);
			this.out.writeShort(nameIndex);
			return this.next++;
		}

		int pkg(int nameIndex) throws java.io.IOException {
			this.out.writeByte(20);
			this.out.writeShort(nameIndex);
			return this.next++;
		}
	}

	// ------------------------------------------------------------ 辅助

	/**
	 * 常量池末端的偏移（即 {@code access_flags} 的起点）。
	 *
	 * <p>只用于构造"常量池完整、{@code this_class} 之后截断"的样本：截断点必须落在常量池完整之后，
	 * 否则廉价前置检查会先拒掉它，就覆盖不到 ASM 解析那一步。</p>
	 */
	private static int endOfConstantPool(byte[] bytes) {
		int pos = 8;
		int count = u2(bytes, pos);
		pos += 2;
		for (int i = 1; i < count; i++) {
			int tag = bytes[pos++] & 0xFF;
			switch (tag) {
				case 1: {                                  // Utf8
					int length = u2(bytes, pos);
					pos += 2 + length;
					break;
				}
				case 7: case 8: case 16: case 19: case 20: // Class / String / MethodType / Module / Package
					pos += 2;
					break;
				case 15:                                   // MethodHandle
					pos += 3;
					break;
				case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
					pos += 4;
					break;
				case 5: case 6:                            // Long / Double 占两个槽
					pos += 8;
					i++;
					break;
				default:
					throw new AssertionError("unexpected constant pool tag " + tag);
			}
		}
		return pos;
	}

	private static int u2(byte[] bytes, int pos) {
		return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
	}

	/** 走真实入口：被拒的类不进 redefine、进 skip 台账、并有用户可见 warn。 */
	private void assertRejectedAndNeverRedefined(Class<?> target, byte[] bytes, String expectedReasonPart) {
		ClassDefinition definition = new ClassDefinition(target, bytes);
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();

		RemoldAgent.RedefineOutcome outcome = RemoldAgent.applyRedefinitions(
		 List.of(definition), List.of(), skips);

		assertTrue(this.instrumentation.redefinitions.isEmpty(),
		 "a rejected definition must never reach redefineClasses: " + this.instrumentation.redefinitions.size()
				+ " call(s) happened");
		assertFalse(outcome.successful().contains(target.getName()),
		 "a rejected class must not be reported as redefined");
		assertTrue(skips.contains(target.getName()),
		 "the rejected class must be named in the round's skip ledger: " + skips.get(target.getName()));
		String verdict = skips.get(target.getName());
		assertNotNull(verdict);
		assertTrue(verdict.startsWith("BYTECODE-GATE"),
		 "the skip reason must point at this gate: " + verdict);
		String warn = this.recorder.warns.stream()
		 .filter(message -> message.contains("BYTECODE-GATE") && message.contains(target.getName()))
		 .findFirst().orElse(null);
		assertNotNull(warn, "the refusal must be user visible through warn (never DEBUG-gated): " + this.recorder.warns);
		assertTrue(warn.contains(expectedReasonPart), "the warning must carry the concrete reason: " + warn);
	}

	private static Class<?> define(String internalName, byte[] bytes) {
		return new FixtureLoader(RedefineEntryGateTest.class.getClassLoader())
		 .define(internalName.replace('/', '.'), bytes);
	}

	/** 自造一份合法 class 文件（含一个构造器，便于 {@code defineClass} 直接定义）。 */
	private static byte[] validBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
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

	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}

	private static final class RecordingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override public void log(String msg) { }
		@Override public void info(String msg) { }
		@Override public void warn(String msg) { this.warns.add(msg); }
		@Override public void error(String msg) { this.errors.add(msg); }
		@Override public void error(String msg, Throwable t) { this.errors.add(msg + ": " + t); }
	}

	/** 只实现被测路径用到的少数方法；{@code redefineClasses} 只录制，不真的重定义。 */
	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition[]> redefinitions = new ArrayList<>();
		final Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(
		 RedefineEntryGateTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
				case "isRetransformClassesSupported":
				case "isModifiableClass":
				case "isRetransformClassesSupportedByDefault":
					return true;
				case "getAllLoadedClasses":
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "redefineClasses":
					this.redefinitions.add((ClassDefinition[]) args[0]);
					return null;
				case "addTransformer":
				case "removeTransformer":
					return true;
				case "getObjectSize":
					return 0L;
				default:
					return defaultFor(method.getReturnType());
			}
		}

		private static Object defaultFor(Class<?> type) {
			if (!type.isPrimitive()) return null;
			if (type == boolean.class) return false;
			if (type == long.class) return 0L;
			return 0;
		}
	}
}

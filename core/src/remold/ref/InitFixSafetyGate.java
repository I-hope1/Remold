package remold.ref;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import remold.RemoldAgent;
import remold.RuntimeConstants;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static remold.RemoldAgent.log;

/**
 * InitFix 静态安全门（Nest 视图、T0 零值等价、P0 最小效应门、参数不可变证明）。
 */
final class InitFixSafetyGate {
	private InitFixSafetyGate() {}

	// ==================== 宿主 Nest 视图（docs/initfix/01-safety-gate.md §3 / §1 的字节码底座） ====================

	/**
	 * 宿主的 Nest 视图：完整成员清单与紧凑的 Nest 字段写入索引。
	 *
	 * <p>用于两件事：{@code docs/initfix/01-safety-gate.md} §3 的"全 Nest 单写证明"，以及 {@code docs/initfix/01-safety-gate.md} §1 T0
	 * 的"该字段在任何地方都没被写过"判定。</p>
	 *
	 * <p><b>只读字节码，绝不 {@code Class.forName}</b>：优先取
	 * {@link RemoldAgent#bytecodeCache}（内存里当前生效的新版本），其次取
	 * {@link remold.BytecodeSources} 登记口（织入期生成的合成类），退化为 ClassLoader
	 * 资源流。任一成员读不到时 {@link #complete()} 为 false，调用方必须按"无法证明"处理
	 * （拒绝），而不是当作"没有第二处写入"。</p>
	 */
	static final class NestView {
		private final List<ClassHierarchyOracle.NestFieldWrite> writes;
		private final boolean                                   complete;

		private NestView(List<ClassHierarchyOracle.NestFieldWrite> writes, boolean complete) {
			this.writes = writes;
			this.complete = complete;
		}

		static NestView of(Class<?> host, ClassNode hostClass) {
			ClassLoader loader = host == null ? null : host.getClassLoader();
			return of(host, hostClass, new HierarchyTreeOracle(loader, hostClass));
		}

		static NestView of(Class<?> host, ClassNode hostClass, HierarchyTreeOracle oracle) {
			try {
				oracle.getNestMembers(hostClass.name);
				return new NestView(oracle.getNestFieldWrites(hostClass.name), true);
			} catch (IllegalStateException unavailable) {
				RemoldAgent.warn("Nest scan incomplete for " + hostClass.name + ": "
					+ unavailable.getMessage()
					+ missingBytecodeHint(unresolvedClassOf(unavailable.getMessage())));
				return new NestView(List.of(), false);
			}
		}

		boolean complete() { return complete; }

		/**
		 * 统计 Nest 内针对指定字段的写指令总数。
		 * <p>只有 {@link #complete()} 为 true 时结果才可用于"零写入"断言。</p>
		 */
		int countPuts(String ownerInternal, String name, String desc, int putOp) {
			int n = 0;
			for (ClassHierarchyOracle.NestFieldWrite write : writes) {
				FieldInsnNode field = write.instruction;
				if (field.getOpcode() != putOp) continue;
				if (!field.owner.equals(ownerInternal)
				    || !field.name.equals(name)
				    || !field.desc.equals(desc)) { continue; }
				n++;
			}
			return n;
		}

		/**
		 * 找出 Nest 内除 {@code self} 之外的第一处写指令。
		 * @return {@code 类.方法描述符} 形式的位置；null 表示不存在第二处写入
		 */
		String firstOtherPut(String ownerInternal, String name, String desc,
		                     int putOp, FieldInsnNode self) {
			for (ClassHierarchyOracle.NestFieldWrite write : writes) {
				FieldInsnNode field = write.instruction;
				if (field.getOpcode() != putOp || field == self) continue;
				if (!field.owner.equals(ownerInternal)
				    || !field.name.equals(name)
				    || !field.desc.equals(desc)) { continue; }
				return write.className + "." + write.methodName + write.methodDesc;
			}
			return null;
		}
	}

	// ==================== T0 零值等价（docs/initfix/01-safety-gate.md §1） ====================

	/**
	 * T0 判定：该新增字段是否"零值等价"——存量实例与静态环境本来就已经是默认值。
	 *
	 * <p>判据：</p>
	 * <ol>
	 *   <li>静态字段带 {@code ConstantValue}：按位判零（{@code static final String S = ""}
	 *       <b>不是</b>零值，因为存量静态环境里是 {@code null}）；</li>
	 *   <li>全 Nest 范围内没有任何写指令：仅声明未赋值，类型默认值即最终值；</li>
	 *   <li>该字段的每一次写都能拿到安全切片，且切片都是"压入类型零值 + 写字段"。</li>
	 * </ol>
	 *
	 * <p>浮点按位判定：{@code -0.0f} / {@code -0.0d} / {@code NaN} 的位模式非零，
	 * 因此<b>不</b>算零值等价（{@code docs/initfix/01-safety-gate.md} §1 明确排除）。第 3 条要求"切片数与写指令数相等"，
	 * 避免出现"只看得到零值那次写、看不到另一次危险写"的静默误判。</p>
	 */
	static boolean isZeroEquivalentField(
	 NestView nest, ClassNode newClass, FieldNode field, boolean isStatic,
	 Map<String, List<InitFix.FieldExtract>> extracts) {

		if (field == null) return false;

		if (isStatic && field.value != null) {
			return isZeroConstant(field.value);
		}
		if (!nest.complete()) return false;

		int putOp  = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		int writes = nest.countPuts(newClass.name, field.name, field.desc, putOp);
		if (writes == 0) return true;

		List<InitFix.FieldExtract> feList = extracts.get(field.name);
		if (feList == null || feList.size() != writes) return false;

		for (InitFix.FieldExtract fe : feList) {
			if (!isZeroValueExtract(fe.instructions(), isStatic, field.desc)) return false;
		}
		return true;
	}

	/** 按位判零的 {@code ConstantValue}（引用常量只有 {@code null} 才是零值，而 null 不落属性）。 */
	private static boolean isZeroConstant(Object cst) {
		if (cst instanceof Integer i) return i == 0;
		if (cst instanceof Long l) return l == 0L;
		if (cst instanceof Float f) return Float.floatToRawIntBits(f) == 0;
		if (cst instanceof Double d) return Double.doubleToRawLongBits(d) == 0;
		if (cst instanceof Short s) return s == 0;
		if (cst instanceof Byte b) return b == 0;
		if (cst instanceof Character c) return c == '\u0000';
		if (cst instanceof Boolean b) return !b;
		return false;
	}

	/**
	 * 切片是否就是"压入类型零值 + 写字段"。
	 * <p>实例字段的期望形态是 {@code [ALOAD 0, <零值>, PUTFIELD]}，
	 * 静态字段是 {@code [<零值>, PUTSTATIC]}（切片经过提取器裁剪，已不含 label/frame）。</p>
	 */
	private static boolean isZeroValueExtract(
	 List<AbstractInsnNode> insns, boolean isStatic, String desc) {

		int expected = isStatic ? 2 : 3;
		if (insns.size() != expected) return false;

		int valueIdx = isStatic ? 0 : 1;
		int putIdx   = isStatic ? 1 : 2;

		if (!isStatic) {
			if (!(insns.get(0) instanceof VarInsnNode v
			      && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) { return false; }
		}
		if (!(insns.get(putIdx) instanceof FieldInsnNode f)
		    || f.getOpcode() != (isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD)) { return false; }

		return isZeroConstantInsn(insns.get(valueIdx), desc);
	}

	/** 单条指令是否是字段描述符对应的类型零值。 */
	private static boolean isZeroConstantInsn(AbstractInsnNode n, String desc) {
		if (n == null || desc == null || desc.isEmpty()) return false;

		char    c   = desc.charAt(0);
		boolean ref = c == 'L' || c == '[';
		boolean isJ = c == 'J';
		boolean isF = c == 'F';
		boolean isD = c == 'D';
		boolean isI = !ref && !isJ && !isF && !isD;   // Z/B/C/S/I

		if (ref) return n.getOpcode() == Opcodes.ACONST_NULL;
		if (n.getOpcode() == Opcodes.ICONST_0) return isI;
		if (n.getOpcode() == Opcodes.LCONST_0) return isJ;
		if (n.getOpcode() == Opcodes.FCONST_0) return isF;
		if (n.getOpcode() == Opcodes.DCONST_0) return isD;

		if (n instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			if (cst instanceof Integer i) return isI && i == 0;
			if (cst instanceof Long l) return isJ && l == 0L;
			if (cst instanceof Float f) return isF && Float.floatToRawIntBits(f) == 0;
			if (cst instanceof Double d) return isD && Double.doubleToRawLongBits(d) == 0;
		}
		return false;
	}

	// ==================== P0 最小效应防御（docs/initfix/01-safety-gate.md §2 的完整 8 位掩码属于 P2） ====================

	/** 基础集合的无参构造：{@code ALLOC_PURE}，永不拦截（docs/initfix/01-safety-gate.md §2.1 要求"严防误杀"）。 */
	private static final Set<String> PURE_NOARG_CTOR_OWNERS = Set.of(
	 "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector", "java/util/Stack",
	 "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap", "java/util/Hashtable",
	 "java/util/WeakHashMap", "java/util/IdentityHashMap",
	 "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet",
	 "java/util/ArrayDeque", "java/util/PriorityQueue",
	 "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentLinkedQueue",
	 "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
	 "java/lang/StringBuilder", "java/lang/StringBuffer");

	/** Logger 工厂：新增 {@code private static final Logger LOG = ...} 字段依赖它。 */
	private static final Set<String> LOGGER_FACTORY_OWNERS = Set.of(
	 "org/slf4j/LoggerFactory", "java/util/logging/Logger",
	 "org/apache/logging/log4j/LogManager", "org/apache/commons/logging/LogFactory");

	/** Logger 类型：其 {@code isXxxEnabled}/{@code getXxx} 是纯查询，放行；落地方法走黑名单。 */
	private static final Set<String> LOGGER_TYPES = Set.of(
	 "org/slf4j/Logger", "java/util/logging/Logger",
	 "org/apache/logging/log4j/Logger", "org/apache/commons/logging/Log");

	/** ThreadLocal 家族（{@code InheritableThreadLocal} 复用的是同一套方法名）。 */
	private static final Set<String> THREAD_LOCAL_OWNERS = Set.of(
	 "java/lang/ThreadLocal", "java/lang/InheritableThreadLocal");

	/**
	 * 字段描述符层面的"按线程的值"类型。
	 *
	 * <p>这类字段在构造器里的<b>使用</b>（不只是初始化式）无法被补丁重放：补丁只在
	 * 热更线程上单线程跑，而构造器当时是在应用线程上跑的，per-thread 副本根本不是同一份。
	 * 因此它们默认被"后续加工检查"拒绝；{@code @RemoldReinit} 可以显式解锁，但必须告警。</p>
	 */
	private static final Set<String> THREAD_AFFINE_DESCS = Set.of(
	 "Ljava/lang/ThreadLocal;", "Ljava/lang/InheritableThreadLocal;");

	static boolean isThreadAffineType(String desc) {
		return desc != null && THREAD_AFFINE_DESCS.contains(desc);
	}

	/**
	 * ThreadLocal 家族里依赖"执行线程"的方法：{@code get}/{@code initialValue}/{@code childValue}
	 * 读的是<b>当前线程</b>的副本（{@code docs/initfix/01-safety-gate.md} §2 bit 4 {@code READS_MUTABLE}），{@code set}/{@code remove}
	 * 变异堆状态（bit 5 {@code MUTATES_HEAP}）。
	 *
	 * <p>补丁永远在热更线程上执行，而实例是在应用线程上构造的 —— 两者不是同一条线程，
	 * 重算必然读到另一份（通常是空的）副本。实测：构造线程上 {@code snapshot="abc"}，
	 * 补丁线程算出 {@code ""} 并静默写入，属"宁可不补"要拦的那类。
	 * {@code withInitial} 只是分配一个新的 ThreadLocal，不读任何线程状态，保持放行。</p>
	 */
	private static final Set<String> THREAD_LOCAL_STATEFUL = Set.of(
	 "get", "set", "remove", "initialValue", "childValue");

	/** 可复用 builder 的类型：两者都是 final 类，不存在"宿主自身即接收者"的情况。 */
	private static final Set<String> BUILDER_OWNERS = Set.of(
	 "java/lang/StringBuilder", "java/lang/StringBuffer");

	/**
	 * builder 的变异方法。这些方法本身必须放行（{@code new StringBuilder().append(..)}
	 * 是 {@code docs/initfix/01-safety-gate.md} §2 明确要求豁免的 {@code ALLOC_PURE}），所以判定落在<b>接收者</b>上，
	 * 见 {@link #builderMutatorReason}。
	 */
	private static final Set<String> BUILDER_MUTATORS = Set.of(
	 "append", "insert", "delete", "deleteCharAt", "replace", "reverse",
	 "setLength", "setCharAt", "ensureCapacity", "trimToSize");

	/**
	 * P0 版最小效应判定。
	 *
	 * <p>判决顺序：</p>
	 * <ol>
	 *   <li><b>精确危险重载</b>：环境依赖的个别重载（无参 {@code String.toUpperCase()}、
	 *       默认字符集的 {@code getBytes()}/{@code new String(byte[])}、{@code String.format}、
	 *       {@code String.intern()}）；</li>
	 *   <li><b>白名单</b>：基础集合无参构造、Logger 工厂与纯查询、{@code Objects.requireNonNull}、
	 *       Kotlin {@code Intrinsics}、不可变集合工厂 —— 优先级高于第 3 步的包级黑名单，
	 *       这正是"严防误杀基础集合构造与 Logger"的落点；</li>
	 *   <li><b>黑名单</b>：非确定性（时间/随机/identityHashCode/默认时区与字符集）、
	 *       线程局部堆状态（ThreadLocal 的 get/set/remove）、反射与动态调用、
	 *       进程与类加载、文件/网络 IO、日志输出；</li>
	 *   <li><b>A+ exception for the {@code Object.toString} coercion</b>: the blacklist item
	 *       {@code identity-dependent dispatch java/lang/Object.toString} is skipped when the
	 *       receiver is proven to be built inside the slice out of constants, checkcasts and one
	 *       of the whitelisted {@code kotlin.text.StringsKt} trim functions
	 *       (see {@link #allowedStringCoercion}). {@code hashCode} is never exempted.</li>
	 *   <li><b>接收者敏感的可复用 builder 判定</b>：{@code StringBuilder}/{@code StringBuffer}
	 *       的变异方法，接收者不是本切片内 {@code NEW} 出来的对象时拒绝
	 *       （见 {@link #builderMutatorReason}）；</li>
	 *   <li>未命中者 P0 一律放行 —— 这一步只做"明确危险"的负向拦截，
	 *       真正的白名单准入（{@code ALLOWED_MASK}）在 P2 落地。</li>
	 * </ol>
	 * @return null 表示放行；否则返回拒绝原因
	 */
	static String effectReason(AbstractInsnNode n, InsnList insns,
	                                   Frame<SourceValue>[] frames,
	                                   Set<AbstractInsnNode> collected) {
		if (n instanceof MethodInsnNode m) {
			if (isImpureOverload(m)) {
				return "environment-dependent call " + m.owner + "." + m.name + m.desc;
			}
			if (isWhitelistedCall(m)) return null;
			if (allowedStringCoercion(m, insns, frames, collected)) return null;
			return blacklistedCallReason(m);
		}
		if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
			if ("java/lang/System".equals(f.owner)
			    && ("out".equals(f.name) || "err".equals(f.name) || "in".equals(f.name))) {
				return "reads process-global stream java/lang/System." + f.name;
			}
			if ("java/io/File".equals(f.owner) && f.name.startsWith("separator")) {
				return "reads platform-dependent java/io/File." + f.name;
			}
		}
		return null;
	}

	/** {@code docs/initfix/01-safety-gate.md} §2 bit 3 的典型例子：同名重载里依赖默认 Locale / 默认 Charset 的那几个。 */
	private static boolean isImpureOverload(MethodInsnNode m) {
		if (!"java/lang/String".equals(m.owner)) return false;
		String name = m.name, desc = m.desc;
		if ("<init>".equals(name)) {
			return desc.equals("([B)V") || desc.equals("([BII)V");
		}
		if ("toUpperCase".equals(name) || "toLowerCase".equals(name)) {
			return desc.equals("()Ljava/lang/String;");
		}
		if ("getBytes".equals(name)) return desc.equals("()[B");
		if ("format".equals(name) || "formatted".equals(name)) return true;
		return "intern".equals(name);
	}

	/** 误杀白名单：命中即放行，优先于包级黑名单。 */
	private static boolean isWhitelistedCall(MethodInsnNode m) {
		// 已登记的"运行时常量"访问器：调用方已断言它在整个运行期返回同一个值，
		// 于是它对切片来说是常量来源，不是副作用。（见 remold.RuntimeConstants）
		if (remold.RuntimeConstants.isRuntimeConstant(m)) return true;

		if ("<init>".equals(m.name) && "()V".equals(m.desc)
		    && PURE_NOARG_CTOR_OWNERS.contains(m.owner)) { return true; }

		if ("java/util/Objects".equals(m.owner) && m.name.startsWith("requireNonNull")) return true;
		if ("java/lang/Object".equals(m.owner) && "getClass".equals(m.name)) return true;
		if ("java/lang/System".equals(m.owner) && "arraycopy".equals(m.name)) return true;
		if (m.owner.startsWith("kotlin/jvm/internal/Intrinsics")) return true;
		if ("java/util/Collections".equals(m.owner)
		    && (m.name.startsWith("empty") || m.name.startsWith("singleton")
		        || m.name.startsWith("unmodifiable"))) { return true; }
		if ("java/util/Arrays".equals(m.owner)
		    && (m.name.startsWith("asList") || m.name.startsWith("copyOf"))) { return true; }

		// Logger：工厂与纯查询放行；落地方法（info/debug/log/...）不在此列，走黑名单。
		if (m.getOpcode() == Opcodes.INVOKESTATIC && LOGGER_FACTORY_OWNERS.contains(m.owner)
		    && ("getLogger".equals(m.name) || "getLog".equals(m.name))) { return true; }
		if (LOGGER_TYPES.contains(m.owner)
		    && (m.name.startsWith("is") || m.name.startsWith("get"))) { return true; }
		return false;
	}

	/** 黑名单：非确定性 / 线程局部状态 / 环境依赖 / 反射 / 进程 / IO / 日志输出。 */
	private static String blacklistedCallReason(MethodInsnNode m) {
		String owner = m.owner, name = m.name;

		if (THREAD_LOCAL_OWNERS.contains(owner) && THREAD_LOCAL_STATEFUL.contains(name)) {
			return "thread-local heap state " + owner + "." + name
			       + " (value depends on execution thread; recomputing on hotswap thread would yield dirty value)";
		}

		if ("java/lang/System".equals(owner)) {
			switch (name) {
				case "currentTimeMillis", "nanoTime", "identityHashCode", "lineSeparator",
				     "getProperty", "getProperties", "getenv", "console", "gc", "exit",
				     "getSecurityManager", "setProperty", "setProperties":
					return "non-deterministic/environment-dependent call java/lang/System." + name;
				default:
					break;
			}
			return null;
		}
		if ("java/lang/Math".equals(owner) && "random".equals(name)) {
			return "non-deterministic random source java/lang/Math.random";
		}
		if ("java/util/Random".equals(owner) || "java/util/SplittableRandom".equals(owner)
		    || "java/util/concurrent/ThreadLocalRandom".equals(owner)
		    || "java/security/SecureRandom".equals(owner) || owner.startsWith("java/security/")) {
			return "non-deterministic random source " + owner;
		}
		if ("java/util/UUID".equals(owner) && "randomUUID".equals(name)) {
			return "non-deterministic java/util/UUID.randomUUID";
		}
		if ("java/lang/Thread".equals(owner)) {
			return "thread/environment state " + owner + "." + name;
		}
		if ("java/lang/Object".equals(owner) && ("hashCode".equals(name) || "toString".equals(name))) {
			return "identity-dependent dispatch java/lang/Object." + name;
		}
		if ("java/util/Date".equals(owner) || "java/util/Calendar".equals(owner)
		    || "java/time/Clock".equals(owner)) {
			return "reads wall clock " + owner + "." + name;
		}
		if (isWallClockNow(owner, name)) {
			return "reads wall clock " + owner + "." + name;
		}
		if ("java/util/Locale".equals(owner) && "getDefault".equals(name)) {
			return "reads default locale java/util/Locale.getDefault";
		}
		if ("java/util/TimeZone".equals(owner) && "getDefault".equals(name)) {
			return "reads default time zone java/util/TimeZone.getDefault";
		}
		if ("java/nio/charset/Charset".equals(owner) && "defaultCharset".equals(name)) {
			return "reads default charset java/nio/charset/Charset.defaultCharset";
		}
		if (("java/lang/Integer".equals(owner) || "java/lang/Long".equals(owner)
		     || "java/lang/Boolean".equals(owner))
		    && ("getInteger".equals(name) || "getLong".equals(name) || "getBoolean".equals(name))) {
			return "reads system property " + owner + "." + name;
		}

		if (owner.startsWith("java/lang/reflect/") || owner.startsWith("java/lang/invoke/")) {
			return "reflective/dynamic call " + owner + "." + name;
		}
		if ("java/lang/Class".equals(owner)
		    && ("forName".equals(name) || "newInstance".equals(name)
		        || name.startsWith("getResource") || "getClassLoader".equals(name)
		        || "getProtectionDomain".equals(name) || "desiredAssertionStatus".equals(name))) {
			return "reflective/environment-dependent call java/lang/Class." + name;
		}
		if ("java/lang/Runtime".equals(owner) || "java/lang/ProcessBuilder".equals(owner)
		    || "java/lang/Process".equals(owner) || "java/lang/ClassLoader".equals(owner)) {
			return "process/loader call " + owner + "." + name;
		}
		if (owner.startsWith("java/io/") || owner.startsWith("java/net/")
		    || owner.startsWith("java/nio/file/") || owner.startsWith("java/nio/channels/")) {
			return "I/O call " + owner + "." + name;
		}
		if (owner.startsWith("java/util/logging/") || owner.startsWith("org/slf4j/")
		    || owner.startsWith("org/apache/logging/")
		    || owner.startsWith("org/apache/commons/logging/")) {
			return "logging output (IO_SYS) " + owner + "." + name;
		}
		return null;
	}

	/** {@code LocalDate.now()} / {@code Instant.now()} 之类的挂钟读取。 */
	private static boolean isWallClockNow(String owner, String name) {
		if (!"now".equals(name)) return false;
		return owner.startsWith("java/time/") || owner.startsWith("java/time/chrono/");
	}

	/**
	 * {@code docs/initfix/01-safety-gate.md} §2「接收者敏感的堆读取判定」+「局部逃逸豁免严格边界」的最小落地。
	 *
	 * <p>两种形态在指令层面只差<b>接收者从哪来</b>：</p>
	 * <ul>
	 *   <li>{@code this.key = new StringBuilder().append(a).toString()}：接收者是切片内
	 *       {@code NEW} 出来的对象，从未逃逸 → {@code ALLOC_PURE}，<b>放行</b>；</li>
	 *   <li>{@code this.key = BUF.append(name).toString()}（{@code BUF} 是可复用缓存字段）：
	 *       接收者来自字段/参数 → 读到的是别人留下的内容，且会把外部堆状态改脏
	 *       （{@code docs/initfix/01-safety-gate.md} §2 bit 4 + bit 5）。更隐蔽的是 {@code setLength(0)} 这类重置语句通常写在
	 *       <b>切片之外</b>，提取时看不见，于是补丁重放会得到 {@code "abcabc"} 这种累积值。
	 *       实测确认过这条路径，<b>拒绝</b>。</li>
	 * </ul>
	 *
	 * <p>完整的逃逸证明（对象是否被赋值给外部字段、是否传给了非纯调用）属于 P2 的代数模型；
	 * 这里只排除"接收者不是切片内新建对象"这一档已实测会读脏的形态。</p>
	 * @return null 表示放行；否则返回拒绝原因
	 */
	static String builderMutatorReason(
	 MethodInsnNode m, InsnList insns, Frame<SourceValue>[] frames) {

		if (m.getOpcode() == Opcodes.INVOKESTATIC) return null;
		if (!BUILDER_OWNERS.contains(m.owner) || !BUILDER_MUTATORS.contains(m.name)) return null;
		if (frames == null) return null;

		int idx = insns.indexOf(m);
		if (idx < 0 || idx >= frames.length) return null;
		Frame<SourceValue> fr = frames[idx];
		if (fr == null) return null;

		int argCount = Type.getArgumentTypes(m.desc).length;
		int recvIdx  = fr.getStackSize() - 1 - argCount;
		if (recvIdx < 0) return null;

		SourceValue recv = fr.getStack(recvIdx);
		if (recv == null) return null;

		StringBuilder origin = new StringBuilder();
		for (AbstractInsnNode src : recv.insns) {
			if (src instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
				if (t.desc.equals(m.owner)) return null;   // 局部逃逸豁免
				appendOrigin(origin, "new " + t.desc);
			} else if (src instanceof FieldInsnNode f) {
				appendOrigin(origin, f.owner + "." + f.name);
			} else if (src instanceof VarInsnNode v) {
				appendOrigin(origin, v.var == 0 ? "this" : "slot" + v.var);
			}
		}
		return "mutates a reusable " + m.owner + " obtained outside the slice"
		       + " (receiver origin: " + (origin.length() == 0 ? "unknown" : origin) + ")";
	}

	private static void appendOrigin(StringBuilder sb, String what) {
		if (sb.length() > 0) sb.append(", ");
		sb.append(what);
	}

	/**
	 * A+ whitelist: the exact {@code owner.name+desc} keys whose result a String field may
	 * coerce with {@code Object.toString()}. Verified with javap on Kotlin 2.3.0 — these are the
	 * only string ops that return {@code CharSequence} and therefore force that coercion.
	 */
	private static final Set<String> STRING_COERCION_PRODUCERS = Set.of(
	 "kotlin/text/StringsKt.trim(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
	 "kotlin/text/StringsKt.trimStart(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
	 "kotlin/text/StringsKt.trimEnd(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;");

	/** True for exactly an INVOKESTATIC call to one of {@link #STRING_COERCION_PRODUCERS}. */
	private static boolean isStringCoercionProducer(AbstractInsnNode n) {
		return n instanceof MethodInsnNode m
		       && m.getOpcode() == Opcodes.INVOKESTATIC
		       && STRING_COERCION_PRODUCERS.contains(m.owner + "." + m.name + m.desc);
	}

	/** Producers that create no value of their own: constants and identity-preserving casts. */
	private static boolean isInertProducer(AbstractInsnNode n) {
		return n instanceof LdcInsnNode
		       || (n instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST);
	}

	/**
	 * The {@code Object.toString()} coercion itself (or an intermediate one of a chained
	 * expression). Restricted to the virtual/interface dispatch that can actually target
	 * {@code Object.toString} and, deliberately, excludes {@code hashCode} so it keeps being
	 * rejected.
	 */
	private static boolean isIntermediateObjectToString(AbstractInsnNode n) {
		return n instanceof MethodInsnNode m
		       && (m.getOpcode() == Opcodes.INVOKEVIRTUAL
		           || m.getOpcode() == Opcodes.INVOKEINTERFACE)
		       && "java/lang/Object".equals(m.owner)
		       && "toString".equals(m.name)
		       && "()Ljava/lang/String;".equals(m.desc);
	}

	/**
	 * A+ exception for the {@code identity-dependent dispatch java/lang/Object.toString} rule.
	 *
	 * <p>Kotlin's {@code "  a ".trim()} compiles to {@code ldc; checkcast CharSequence;
	 * invokestatic StringsKt.trim; invokevirtual java/lang/Object.toString}, so the coercion is
	 * dispatched through {@code Object} even though the value is always a String. Accept it only
	 * when the receiver is <b>proven</b> to be built inside the slice:</p>
	 * <ol>
	 *   <li>the direct producer (checkcasts ignored) is one of {@link #STRING_COERCION_PRODUCERS};</li>
	 *   <li>every instruction in the receiver's provenance closure is inert, a whitelisted
	 *       producer, or an intermediate {@code Object.toString()} of the same chained
	 *       expression, and all of them lie inside {@code collected};</li>
	 *   <li>fails closed — no frames, no frame, no receiver or an empty closure means "reject",
	 *       so the original rule still covers {@code Any().toString()}, field receivers and
	 *       user-defined methods.</li>
	 * </ol>
	 * {@code hashCode} is deliberately never exempted.
	 */
	static boolean allowedStringCoercion(MethodInsnNode m, InsnList insns,
	                                             Frame<SourceValue>[] frames,
	                                             Set<AbstractInsnNode> collected) {
		if (!isIntermediateObjectToString(m)) return false;
		if (frames == null || collected == null) return false;

		int idx = insns.indexOf(m);
		if (idx < 0 || idx >= frames.length) return false;
		Frame<SourceValue> fr = frames[idx];
		if (fr == null) return false;

		int recvIdx = fr.getStackSize() - 1 - Type.getArgumentTypes(m.desc).length;
		if (recvIdx < 0) return false;
		SourceValue recv = fr.getStack(recvIdx);
		if (recv == null || recv.insns.isEmpty()) return false;

		int directIdx = -1;
		for (AbstractInsnNode src : recv.insns) {
			int at = insns.indexOf(src);
			if (at < 0 || !collected.contains(src)) return false;   // rule 2: slice-local only
			if (isStringCoercionProducer(src)) {
				if (at > directIdx) directIdx = at;                  // rule 1: the direct producer
			} else if (isIntermediateObjectToString(src)) {
				// A chained coercion: "  a ".trim().trimStart() emits two Object.toString calls
				// and AliasInterpreter merges the first into the second one's closure. Admitting
				// it is sound because `src` is in `collected`, so the in-order slice walk
				// (minIdx..putIdx, first failure wins) vetted it on its own beforehand.
			} else if (!isInertProducer(src)) {
				return false;                                        // rule 2: nothing else
			}
		}
		return directIdx >= 0;
	}

	// ==================== 构造器参数 -> 字段扫描 ====================

	/**
	 * 扫描 {@code <init>} 里 {@code ALOAD 0; XLOAD n; PUTFIELD this.f} 形态的
	 * "参数持久化到字段"模式，建立 {@code slot -> field} 回溯映射。
	 *
	 * <p><b>{@code docs/initfix/01-safety-gate.md} §3 源字段不可变证明</b>：映射成立后，切片段里的 {@code ALOAD n} 会被改写为
	 * {@code ALOAD 0; GETFIELD this.f}。补丁在 redefine 之后才执行，读到的是字段
	 * <b>当前</b>值而非构造时的值，所以只有当 {@code f} 在构造完成后不可能再变时改写才等价：</p>
	 * <ol>
	 *   <li><b>条件 A</b>：{@code f} 带 {@code ACC_FINAL}（JVM 只允许在构造器内写入）；</li>
	 *   <li><b>条件 B</b>：{@code f} 非 final，但为 {@code private}，且{@link NestView 全 Nest 范围内}
	 *       除本次 pattern 外不存在第二处 {@code PUTFIELD}（排除 setter 二次修改、其它构造器写入、
	 *       内部类跨实例写入）。证明通过后该字段的读取在效应审查中按 {@code READS_FINAL} 等价处理。</li>
	 * </ol>
	 * <p>二者都不满足时拒绝映射 —— 宁可不补，也不能拿被改过的值去算。</p>
	 * @param host 宿主类（仅用于取 ClassLoader 读 Nest 成员资源，<b>不做</b> {@code Class.forName}）
	 * @param nest 宿主 Nest 视图
	 * @return 可用映射 + 被拒槽位的真实原因（原因会被透传进 {@link InitFix.PatchReport}）
	 */
	static InitFix.ParamScan scanParamFields(
	 Class<?> host, ClassNode hostClass, MethodNode init, NestView nest) {

		Map<Integer, InitFix.ParamField> map      = new HashMap<>();
		Map<Integer, String>     rejected = new LinkedHashMap<>();
		InsnList                 insns    = init.instructions;
		List<AbstractInsnNode>   real     = filterReal(insns);

		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i);
			AbstractInsnNode b = real.get(i + 1);
			AbstractInsnNode c = real.get(i + 2);

			if (!(a instanceof VarInsnNode va)
			    || va.getOpcode() != Opcodes.ALOAD || va.var != 0) { continue; }
			if (!(b instanceof VarInsnNode vb)
			    || !isLoadOfParam(vb)) { continue; }
			if (!(c instanceof FieldInsnNode fc)
			    || fc.getOpcode() != Opcodes.PUTFIELD) { continue; }
			if (!fc.owner.equals(hostClass.name)) continue;

			int slot = vb.var;
			if (map.containsKey(slot)) continue;

			int    startOrig  = insns.indexOf(a);
			int    putOrig    = insns.indexOf(c);
			String ctrlReason = InitFixSliceExtractor.checkControlDependency(insns, startOrig, putOrig);
			if (ctrlReason != null) {
				rejectParamSlot(rejected, slot, "pattern has control dependency: " + ctrlReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: pattern has control dependency: "
				    + ctrlReason);
				continue;
			}

			// 参数类型必须与字段描述符严格一致，否则替换成 GETFIELD 后
			// 后续指令期望的类型会不匹配，触发 VerifyError
			if (!paramTypeMatches(init.desc, slot, fc.desc)) {
				rejectParamSlot(rejected, slot, "parameter type != field type " + fc.desc);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: parameter type != field type "
				    + fc.desc);
				continue;
			}

			// docs/initfix/01-safety-gate.md §3：源字段必须可证明不可变
			FieldNode source = null;
			for (FieldNode fn : hostClass.fields) {
				if (fn.name.equals(fc.name) && fn.desc.equals(fc.desc)) {
					source = fn;
					break;
				}
			}
			if (source == null) {
				rejectParamSlot(rejected, slot, "source field declaration not found");
				continue;
			}
			String immutableReason = sourceFieldNotImmutableReason(hostClass, source, fc, nest);
			if (immutableReason != null) {
				rejectParamSlot(rejected, slot, "source field '" + source.name
				                                + "' is not provably immutable: " + immutableReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: source field '" + source.name
				    + "' is not provably immutable: " + immutableReason);
				continue;
			}

			int paramWidth = (vb.getOpcode() == Opcodes.LLOAD
			                  || vb.getOpcode() == Opcodes.DLOAD) ? 2 : 1;

			String unsafeReason = null;

			for (int k = 0; k < i; k++) {
				AbstractInsnNode n = real.get(k);
				if (n instanceof VarInsnNode v && isStore(v.getOpcode())) {
					int storeWidth = (v.getOpcode() == Opcodes.LSTORE
					                  || v.getOpcode() == Opcodes.DSTORE) ? 2 : 1;
					if (slotsOverlap(slot, paramWidth, v.var, storeWidth)) {
						unsafeReason = "slot overwritten before pattern by xSTORE at real index "
						               + k + " (store slot=" + v.var + " width=" + storeWidth + ")";
						break;
					}
				} else if (n instanceof IincInsnNode inc) {
					if (slotsOverlap(slot, paramWidth, inc.var, 1)) {
						unsafeReason = "slot incremented before pattern by IINC at real index "
						               + k + " (iinc slot=" + inc.var + ")";
						break;
					}
				}
			}
			if (unsafeReason != null) {
				rejectParamSlot(rejected, slot, unsafeReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: " + unsafeReason);
				continue;
			}

			for (int k = i + 3; k < real.size(); k++) {
				AbstractInsnNode n = real.get(k);

				if (n instanceof VarInsnNode v && isStore(v.getOpcode())) {
					int storeWidth = (v.getOpcode() == Opcodes.LSTORE
					                  || v.getOpcode() == Opcodes.DSTORE) ? 2 : 1;
					if (slotsOverlap(slot, paramWidth, v.var, storeWidth)) {
						unsafeReason = "slot overwritten by xSTORE at real index " + k
						               + " (store slot=" + v.var + " width=" + storeWidth + ")";
						break;
					}
				} else if (n instanceof IincInsnNode inc) {
					if (slotsOverlap(slot, paramWidth, inc.var, 1)) {
						unsafeReason = "slot incremented by IINC at real index " + k
						               + " (iinc slot=" + inc.var + ")";
						break;
					}
				} else if (n instanceof FieldInsnNode f
				           && f.getOpcode() == Opcodes.PUTFIELD
				           && f.owner.equals(hostClass.name)
				           && f.name.equals(fc.name)) {
					unsafeReason = "field " + fc.name + " overwritten by PUTFIELD at real index " + k;
					break;
				}
			}
			if (unsafeReason != null) {
				rejectParamSlot(rejected, slot, unsafeReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: " + unsafeReason);
				continue;
			}

			map.put(slot, new InitFix.ParamField(source, slot));
		}
		return new InitFix.ParamScan(map, rejected);
	}

	/** 记录被拒槽位的原因（首次为准，保留最贴近根因的那条）。 */
	private static void rejectParamSlot(Map<Integer, String> rejected, int slot, String reason) {
		if (rejected != null) rejected.putIfAbsent(slot, reason);
	}

	/** 记录字段被拒的真实原因（首次为准）。 */
	static void recordReason(Map<String, String> outReasons, String field, String reason) {
		if (outReasons != null) outReasons.putIfAbsent(field, reason);
	}

	/**
	 * {@code docs/initfix/01-safety-gate.md} §3 源字段不可变证明。
	 * @param pattern 本次触发映射的 {@code PUTFIELD}（Nest 扫描时按对象身份排除）
	 * @return null 表示可证明构造后不再变化；否则返回拒绝原因
	 */
	private static String sourceFieldNotImmutableReason(
	 ClassNode hostClass, FieldNode field, FieldInsnNode pattern, NestView nest) {

		// 条件 A：final（JVM 只允许在声明类构造器内写入）
		if ((field.access & Opcodes.ACC_FINAL) != 0) return null;

		// 条件 B：private + 全 Nest 单写
		if ((field.access & Opcodes.ACC_PRIVATE) == 0) {
			return "not final and not private (neither condition A nor B satisfied)";
		}
		if (!nest.complete()) {
			return "cannot read every nest member to prove a single write";
		}
		String where = nest.firstOtherPut(hostClass.name, field.name, field.desc,
		 Opcodes.PUTFIELD, pattern);
		if (where != null) {
			return "private but written again at " + where;
		}
		return null;
	}

	/**
	 * 校验方法描述符中 {@code slot} 处的参数类型是否与 {@code fieldDesc} 一致。
	 * <p>slot 0 是 this；long/double 占 2 槽。</p>
	 */
	private static boolean paramTypeMatches(String methodDesc, int slot, String fieldDesc) {
		Type[] args = Type.getArgumentTypes(methodDesc);
		int    cur  = 1;
		for (Type t : args) {
			if (cur == slot) {
				return t.getDescriptor().equals(fieldDesc);
			}
			cur += t.getSize();
			if (cur > slot) return false;
		}
		return false;
	}

	private static List<AbstractInsnNode> filterReal(InsnList insns) {
		List<AbstractInsnNode> real = new ArrayList<>(insns.size());
		for (AbstractInsnNode n : insns) {
			if (n.getOpcode() != -1) real.add(n);
		}
		return real;
	}

	private static boolean slotsOverlap(int s1, int w1, int s2, int w2) {
		return s1 < s2 + w2 && s2 < s1 + w1;
	}

	static boolean isLoadOfParam(VarInsnNode v) {
		int op = v.getOpcode();
		boolean isLoad = op == Opcodes.ALOAD || op == Opcodes.ILOAD
		                 || op == Opcodes.LLOAD || op == Opcodes.FLOAD
		                 || op == Opcodes.DLOAD;
		return isLoad && v.var != 0;
	}

	private static boolean isStore(int op) {
		return op == Opcodes.ASTORE || op == Opcodes.ISTORE || op == Opcodes.LSTORE
		       || op == Opcodes.FSTORE || op == Opcodes.DSTORE;
	}

	static boolean isPrimitiveDesc(String desc) {
		if (desc == null || desc.isEmpty()) return false;
		char c = desc.charAt(0);
		return c == 'Z' || c == 'B' || c == 'C' || c == 'S'
		       || c == 'I' || c == 'J' || c == 'F' || c == 'D';
	}

	static boolean isImmutableType(String desc) {
		if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L') return false;
		String c = desc.substring(1, desc.length() - 1);
		switch (c) {
			case "java/lang/String":
			case "java/lang/Integer":
			case "java/lang/Long":
			case "java/lang/Short":
			case "java/lang/Byte":
			case "java/lang/Character":
			case "java/lang/Boolean":
			case "java/lang/Float":
			case "java/lang/Double":
			case "java/lang/Class":
			case "java/math/BigDecimal":
			case "java/math/BigInteger":
			case "java/time/Instant":
			case "java/time/Duration":
			case "java/time/LocalDate":
			case "java/time/LocalTime":
			case "java/time/LocalDateTime":
			case "java/time/ZonedDateTime":
			case "java/util/UUID":
				return true;
			default:
				return false;
		}
	}


	/**
	 * 从"&lt;原因&gt;: &lt;类名&gt;"形态的诊断文案里取出类名（取不到返回 {@code null}）。
	 */
	static String unresolvedClassOf(String diagnostic) {
		if (diagnostic == null) return null;
		int separator = diagnostic.lastIndexOf(": ");
		return separator < 0 ? null : diagnostic.substring(separator + 2).trim();
	}

	/**
	 * 诊断归因：读不到字节的对象若是「织入期生成的合成内部类」，点名并给出唯一的补救路径。
	 *
	 * <p>Mixin 的 {@code InnerClassGenerator} 在织入期按 {@code Target$Anonymous$<hex>} 生成合成内部类，
	 * 并把名字登记进目标类的 {@code nestMembers}。这类字节磁盘上没有、也不经过 Remold 的
	 * {@code ClassFileTransformer}，于是 Nest 扫描只能判 {@code incomplete}、成员可见性只能判
	 * "无法确定"，两条路径都 fail-closed。不把归因写进日志，读者会去找一个根本不存在的
	 * {@code .class}，而真正的补救动作是让织入方把字节回灌到 {@link remold.BytecodeSources}。</p>
	 *
	 * @param className 类名（内部名或点分名）
	 * @return 归因后缀；无提示时返回空串
	 */
	static String missingBytecodeHint(String className) {
		if (className == null || className.isEmpty()) return "";
		int separator = Math.max(className.lastIndexOf('/'), className.lastIndexOf('.'));
		String simple = className.substring(separator + 1);
		if (simple.contains("$Anonymous$")) {
			return " — " + simple + " is a synthetic inner class generated at weaving time"
				+ " (Mixin InnerClassGenerator): it has no .class on the classpath and never passes"
				+ " Remold's ClassFileTransformer. Its bytes must be registered with"
				+ " remold.BytecodeSources (key = internal name) by the weaving plugin; without that"
				+ " bytecode re-feed the metadata stays unresolvable and this decision stays"
				+ " fail-closed.";
		}
		return " — if this member is a synthetic inner class produced by a weaving framework"
			+ " (Mixin/Forge/NeoForge), its bytecode exists only in memory and must be re-fed to"
			+ " remold.BytecodeSources; otherwise this stays fail-closed by design.";
	}

}

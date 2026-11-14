package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 Mixin 重织入产物的<b>生成成员名</b>与<b>成员顺序</b>对齐回 JVM 已加载的基线字节码。
 *
 * <p><b>问题</b>：只改了 Mixin 方法体时，重织入会重新生成所有 Mixin 合成成员
 * （{@code handler$z*}/{@code redirect$z*}/{@code wrapOperation$z*}/{@code localvar$z*} …）。
 * 这些名字里的 {@code $z<id>$} 段是 Mixin 的会话内唯一前缀，热重载后会整体漂移
 * （例如 {@code handler$zce000$cfg$foo} 变成 {@code handler$zzc000$cfg$foo}）。对 JVM 来说，
 * 这就从"一个方法体变了"变成"删掉一批方法、新增一批方法"的结构级变更；JBR 的增强重定义
 * 在 link/verify 受影响子类时于是失败（{@code InternalError}）。</p>
 *
 * <p><b>判据</b>：</p>
 * <ol>
 *   <li>识别两侧的 Mixin 生成成员：{@code (handler|redirect|wrapOperation|modify|localvar|args|md)$<id>$<tail>}；</li>
 *   <li>按 <b>(生成器前缀, {@code $<id>$} 之后的 tail, 描述符)</b> 配对；同一 key 多次出现时按出现顺序配对
 *       （tail 通常已包含 Mixin 配置名 + 宿主方法名，足以唯一确定）；</li>
 *   <li>用 ASM {@link ClassRemapper} 把候选的新名字映射回基线名字，同时改写
 *       {@code MethodInsnNode} 与 {@code InvokeDynamicInsnNode.bsmArgs} 里的 {@link Handle}；</li>
 *   <li>按基线顺序重排 {@code methods}/{@code fields}，候选中基线不存在的新成员追加在末尾。</li>
 * </ol>
 *
 * <p>纯函数：只读入参字节，不依赖类加载/反射；方法体、StackMapTable、注解原样保留。</p>
 */
public final class MixinMemberAlign {

	private MixinMemberAlign() {}

	/** Mixin 生成成员的命名模式：{@code prefix$<alnum id>$tail}。 */
	private static final Pattern GENERATED = Pattern.compile(
		"^(handler|redirect|wrapOperation|modify|localvar|args|md)\\$[a-z0-9]+\\$.+$");

	/** 成员名 + 描述符的轻量视图（方法/字段共用）。 */
	private static final class Member {
		final String name;
		final String desc;

		Member(String name, String desc) {
			this.name = name;
			this.desc = desc;
		}
	}

	public static final class Result {
		public final byte[]       bytes;
		public final int          renamed;
		public final boolean      reordered;
		public final List<String> missingFromCandidate;   // 基线有、候选重映射后仍没有的生成成员（墓碑桩补上后为空）
		public final List<String> addedInCandidate;       // 候选新增（基线没有）的生成成员
		public final List<String> tombstoned;             // 本次保活为最小空操作桩的基线生成方法
		public final List<String> tombstonedFields;       // 本次保活为墓碑字段的基线字段

		Result(byte[] bytes, int renamed, boolean reordered,
		       List<String> missingFromCandidate, List<String> addedInCandidate,
		       List<String> tombstoned, List<String> tombstonedFields) {
			this.bytes                = bytes;
			this.renamed              = renamed;
			this.reordered            = reordered;
			this.missingFromCandidate = Collections.unmodifiableList(missingFromCandidate);
			this.addedInCandidate     = Collections.unmodifiableList(addedInCandidate);
			this.tombstoned           = Collections.unmodifiableList(tombstoned);
			this.tombstonedFields     = Collections.unmodifiableList(tombstonedFields);
		}

		public boolean changed() {
			return renamed > 0 || reordered || !tombstoned.isEmpty() || !tombstonedFields.isEmpty();
		}
	}

	/**
	 * 该字节码里是否存在 <b>Mixin 生成/注入</b>的成员——决定要不要进入名称/顺序对齐。
	 *
	 * <p>判据必须是"Mixin 产物"而不是"任何带 {@code $} 的合成字段"：javac / JVM 自己也会生成
	 * 同名形态的合成字段（枚举的 {@code $VALUES}、内部类的外部实例引用 {@code this$0}、
	 * {@code $assertionsDisabled}、switch-on-enum 的 {@code $SwitchMap$*}），把它们当成 Mixin
	 * 标记会让对齐器对普通类执行，{@code orderMembers} 在 {@code ClassDiffUtil} 之前把字段表
	 * 恢复成基线顺序，从而<b>吞掉真实的字段/枚举顺序变更</b>（前线 P0-2：枚举顺序门被抢跑，
	 * 运行期仍钉在旧顺序、追加常量静默丢失）。</p>
	 *
	 * <p>Mixin 的方法生成物有稳定前缀（{@link #GENERATED}）；{@code @Unique} 字段没有统一前缀，
	 * 仍用"合成 + 名字含 {@code $}"兜底，但先扣掉已知的 JVM / javac 合成形态。</p>
	 */
	public static boolean hasMixinGeneratedMembers(byte[] bytecode) {
		if (bytecode == null) return false;
		final boolean[] found = {false};
		try {
			// Only inspect member headers so large classes do not allocate MethodNode/InsnList.
			new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public FieldVisitor visitField(int access, String name, String descriptor,
				                               String signature, Object value) {
					if (!found[0] && looksLikeMixinField(access, name)) {
						found[0] = true;
					}
					return null;
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					if (!found[0] && isGenerated(name)) {
						found[0] = true;
					}
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable ignored) {
			return false;
		}
		return found[0];
	}

	/** 字段层面的 Mixin 标记：生成成员名，或"合成 + {@code $}"且不属于 JVM/javac 的合成形态。 */
	private static boolean looksLikeMixinField(int access, String name) {
		if (name == null) return false;
		if (isGenerated(name)) return true;
		if ((access & Opcodes.ACC_SYNTHETIC) == 0 || !name.contains("$")) return false;
		return !isJvmSynthesizedFieldName(name);
	}

	/**
	 * JVM / javac 自己生成的合成字段名（不是 Mixin 产物）。
	 *
	 * <p>只列会被热更管线遇到的形态；名字看起来像用户字段的一律不猜，保持原判据的保守方向
	 * （宁可多对齐一次，也不要漏掉真正的 {@code @Unique} 字段墓碑）。</p>
	 */
	private static boolean isJvmSynthesizedFieldName(String name) {
		if ("$VALUES".equals(name) || "$assertionsDisabled".equals(name)) return true;
		// 非静态内部类的外部实例引用：this$0 / this$1 ...
		if (name.startsWith("this$")) {
			String suffix = name.substring("this$".length());
			boolean digits = !suffix.isEmpty();
			for (int i = 0; i < suffix.length(); i++) {
				if (!Character.isDigit(suffix.charAt(i))) { digits = false; break; }
			}
			if (digits) return true;
		}
		// javac 为 switch-on-enum / EnumMap 生成的映射表 holder 字段。
		return name.startsWith("$SwitchMap$") || name.startsWith("$EnumMap$");
	}

	/**
	 * 把 {@code candidate}（重织入产物）的 Mixin 生成成员名与成员顺序对齐到 {@code baseline}（已加载字节码）。
	 *
	 * @throws IllegalArgumentException 入参不可解析、或两者内部类名不一致
	 */
	public static Result align(byte[] candidate, byte[] baseline) {
		if (candidate == null || baseline == null) {
			throw new IllegalArgumentException("candidate/baseline must not be null");
		}
		ClassNode base = readMetadata(baseline);
		ClassNode cand = readMetadata(candidate);
		if (base.name == null || cand.name == null || !base.name.equals(cand.name)) {
			throw new IllegalArgumentException("class name mismatch: baseline=" + base.name
				+ ", candidate=" + cand.name);
		}

		Map<String, String> methodRemap = pairGenerated(methodMembers(base), methodMembers(cand));
		Map<String, String> fieldRemap  = pairGenerated(fieldMembers(base), fieldMembers(cand));
		int renamed = methodRemap.size() + fieldRemap.size();

		byte[] remapped = methodRemap.isEmpty() && fieldRemap.isEmpty()
			? candidate
			: remapReferences(candidate, cand.name, methodRemap, fieldRemap);

		// Probe structure/order mutations on metadata first to avoid decoding method bodies on no-op aligns.
		ClassNode alignedMeta = (remapped == candidate) ? cand : readMetadata(remapped);
		boolean enumClass = isEnum(base) || isEnum(alignedMeta);
		List<String> tombstoned = tombstoneMissing(alignedMeta, base);
		List<String> tombstonedFields = tombstoneMissingFields(alignedMeta, base, enumClass);
		boolean reordered = orderMembers(base, alignedMeta, enumClass);
		byte[] out = remapped;
		if (reordered || !tombstoned.isEmpty() || !tombstonedFields.isEmpty()) {
			ClassNode alignedFull = read(remapped);
			tombstoneMissing(alignedFull, base);
			tombstoneMissingFields(alignedFull, base, enumClass);
			orderMembers(base, alignedFull, enumClass);
			out = write(alignedFull);
		}

		List<String> missing = new ArrayList<>();
		List<String> added   = new ArrayList<>();
		Set<String> candidateGenerated = new HashSet<>();
		Set<String> baselineGenerated  = new HashSet<>();
		for (MethodNode m : alignedMeta.methods) {
			if (isGenerated(m.name)) candidateGenerated.add(m.name + m.desc);
		}
		for (MethodNode m : base.methods) {
			if (!isGenerated(m.name)) continue;
			baselineGenerated.add(m.name + m.desc);
			if (!candidateGenerated.contains(m.name + m.desc)) missing.add(m.name + m.desc);
		}
		for (MethodNode m : alignedMeta.methods) {
			if (isGenerated(m.name) && !baselineGenerated.contains(m.name + m.desc)) {
				added.add(m.name + m.desc);
			}
		}
		return new Result(out, renamed, reordered, missing, added, tombstoned, tombstonedFields);
	}

	/**
	 * 为基线中已消失的字段补墓碑（同名/同描述符/同访问标志，字段表顺序由 {@link #orderMembers} 复原）。
	 *
	 * <p>只处理「基线有、候选无」的<b>字段</b>（Mixin 的 {@code @Unique} 字段是典型来源）。保留字段
	 * 意味着实例内存布局不变：retarget 移走 Mixin 时字段不消失（不缩容），迁回时也不新增（不扩容、
	 * 不再触发 {@code Applying instance field init patch}）。字段值语义上属于"注入已移除"，但墓碑字段
	 * 本身没有读者（重织入产物里不再引用它），保留不会改变业务行为。</p>
	 *
	 * <p><b>枚举常量不复活</b>：{@code RED}/{@code GREEN}/… 也是"基线有、候选无"的字段，但它们的
	 * 存在与顺序是 ordinal 语义的一部分，消费者可能是 switch-on-enum。复活会让删除/重排对
	 * {@code ClassDiffUtil} 不可见——必须让布局门看到真实变更。</p>
	 *
	 * <p>构造器（{@code <init>}）与 {@code <clinit>} 不在此列——它们不是字段。</p>
	 */
	private static List<String> tombstoneMissingFields(ClassNode cand, ClassNode base, boolean enumClass) {
		Set<String> present = new HashSet<>();
		for (FieldNode f : cand.fields) present.add(f.name + f.desc);
		List<String> stubbed = new ArrayList<>();
		for (FieldNode f : base.fields) {
			String key = f.name + f.desc;
			if (present.contains(key)) continue;
			if (enumClass && isEnumConstantField(f, base.name)) continue;
			cand.fields.add(new FieldNode(f.access, f.name, f.desc, f.signature, f.value));
			present.add(key);
			stubbed.add(key);
		}
		return stubbed;
	}

	/** 枚举常量字段（javac 标 {@code ACC_ENUM}；兜底看 static + 自身类型的形态）。 */
	private static boolean isEnumConstantField(FieldNode f, String classInternalName) {
		if ((f.access & Opcodes.ACC_ENUM) != 0) return true;
		return (f.access & Opcodes.ACC_STATIC) != 0
			&& classInternalName != null && ("L" + classInternalName + ";").equals(f.desc);
	}

	private static boolean isEnum(ClassNode node) {
		return (node.access & Opcodes.ACC_ENUM) != 0 || "java/lang/Enum".equals(node.superName);
	}

	/**
	 * 为基线中已消失的 Mixin 生成方法补最小空操作桩（墓碑），并保证方法表只增不减。
	 *
	 * <p>桩体规则（与 {@code LambdaAligner} 的孤儿方法策略一致）：</p>
	 * <ul>
	 *   <li>{@code void} 返回：单条 {@code RETURN}；</li>
	 *   <li>返回类型与某个入参类型相同（{@code @ModifyVariable}/{@code @ModifyArg}/{@code @Redirect}
	 *       的典型形态）：加载该入参并原样返回——把"注入被移除"退化成恒等传递；</li>
	 *   <li>其余返回类型：零值（{@code 0}/{@code 0L}/{@code 0.0f}/{@code 0.0d}/{@code null}）。</li>
	 * </ul>
	 *
	 * <p>只处理 {@link #isGenerated} 命中的合成成员；普通业务方法/构造器/静态初始化块一律不补，
	 * 避免把"确实删掉的方法"伪装成还存在。</p>
	 */
	private static List<String> tombstoneMissing(ClassNode cand, ClassNode base) {
		Set<String> present = new HashSet<>();
		for (MethodNode m : cand.methods) present.add(m.name + m.desc);
		List<String> stubbed = new ArrayList<>();
		for (MethodNode m : base.methods) {
			if (!isGenerated(m.name)) continue;
			String key = m.name + m.desc;
			if (present.contains(key)) continue;
			cand.methods.add(tombstone(m));
			present.add(key);
			stubbed.add(key);
		}
		return stubbed;
	}

	private static MethodNode tombstone(MethodNode base) {
		int access = base.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
		String[] exceptions = base.exceptions == null ? null
			: base.exceptions.toArray(new String[0]);
		MethodNode stub = new MethodNode(access, base.name, base.desc, base.signature, exceptions);
		Type ret = Type.getReturnType(base.desc);
		InsnList code = new InsnList();
		if (ret.getSort() == Type.VOID) {
			code.add(new InsnNode(Opcodes.RETURN));
		} else {
			Type[] args = Type.getArgumentTypes(base.desc);
			int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
			Type sameTypeArg = null;
			int sameTypeSlot = -1;
			int locals = slot;
			for (Type arg : args) {
				if (sameTypeArg == null && arg.equals(ret)) {
					sameTypeArg = arg;
					sameTypeSlot = slot;
				}
				slot += arg.getSize();
			}
			locals = slot;
			if (sameTypeArg != null) {
				code.add(new VarInsnNode(sameTypeArg.getOpcode(Opcodes.ILOAD), sameTypeSlot));
			} else {
				code.add(zeroOf(ret));
			}
			code.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
			stub.maxStack = Math.max(ret.getSize(), 1);
			stub.maxLocals = Math.max(locals, 1);
		}
		if (ret.getSort() == Type.VOID) {
			stub.maxStack = 0;
			stub.maxLocals = Math.max(argumentSlots(base.desc, (access & Opcodes.ACC_STATIC) != 0), 1);
		}
		stub.instructions = code;
		stub.localVariables = null;
		stub.tryCatchBlocks = null;
		stub.visibleAnnotations = null;
		stub.invisibleAnnotations = null;
		return stub;
	}

	private static int argumentSlots(String desc, boolean isStatic) {
		int slots = isStatic ? 0 : 1;
		for (Type arg : Type.getArgumentTypes(desc)) slots += arg.getSize();
		return slots;
	}

	private static InsnNode zeroOf(Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN:
			case Type.BYTE:
			case Type.CHAR:
			case Type.SHORT:
			case Type.INT:
				return new InsnNode(Opcodes.ICONST_0);
			case Type.LONG:
				return new InsnNode(Opcodes.LCONST_0);
			case Type.FLOAT:
				return new InsnNode(Opcodes.FCONST_0);
			case Type.DOUBLE:
				return new InsnNode(Opcodes.DCONST_0);
			default:
				return new InsnNode(Opcodes.ACONST_NULL);
		}
	}

	/** 生成 {@code 候选名 -> 基线名} 映射。 */
	private static Map<String, String> pairGenerated(List<Member> baseMembers, List<Member> candMembers) {
		Map<String, List<String>> byKey = new LinkedHashMap<>();
		for (Member m : baseMembers) {
			if (!isGenerated(m.name)) continue;
			byKey.computeIfAbsent(key(m), k -> new ArrayList<>()).add(m.name);
		}
		Map<String, Integer> used = new HashMap<>();
		Map<String, String> remap = new LinkedHashMap<>();
		for (Member m : candMembers) {
			if (!isGenerated(m.name)) continue;
			List<String> targets = byKey.get(key(m));
			if (targets == null || targets.isEmpty()) continue;
			int index = used.getOrDefault(key(m), 0);
			if (index >= targets.size()) continue;
			used.put(key(m), index + 1);
			String target = targets.get(index);
			if (!target.equals(m.name)) remap.put(m.name, target);
		}
		return remap;
	}

	private static List<Member> methodMembers(ClassNode node) {
		List<Member> out = new ArrayList<>(node.methods.size());
		for (MethodNode m : node.methods) out.add(new Member(m.name, m.desc));
		return out;
	}

	private static List<Member> fieldMembers(ClassNode node) {
		List<Member> out = new ArrayList<>(node.fields.size());
		for (FieldNode f : node.fields) out.add(new Member(f.name, f.desc));
		return out;
	}

	private static String key(Member m) {
		return keyType(m.name) + '\u0000' + tail(m.name) + '\u0000' + m.desc;
	}

	private static ClassNode readMetadata(byte[] bytes) {
		return read(bytes, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	private static ClassNode read(byte[] bytes) {
		return read(bytes, 0);
	}

	private static ClassNode read(byte[] bytes, int parsingOptions) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, parsingOptions);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** 用 ASM ClassRemapper 改写候选字节码中的生成成员名与全部引用。 */
	private static byte[] remapReferences(byte[] candidate, final String selfInternalName,
	                                      final Map<String, String> methodRemap,
	                                      final Map<String, String> fieldRemap) {
		Remapper remapper = new Remapper() {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				if (selfInternalName.equals(owner) && methodRemap.containsKey(name)) {
					return methodRemap.get(name);
				}
				return name;
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				if (selfInternalName.equals(owner) && fieldRemap.containsKey(name)) {
					return fieldRemap.get(name);
				}
				return name;
			}

			@Override
			public String mapInvokeDynamicMethodName(String name, String descriptor) {
				return methodRemap.getOrDefault(name, name);
			}

			@Override
			public Object mapValue(Object value) {
				if (value instanceof Handle) {
					Handle h = (Handle) value;
					String mapped = mapMethodName(h.getOwner(), h.getName(), h.getDesc());
					if (!mapped.equals(h.getName())) {
						return new Handle(h.getTag(), h.getOwner(), mapped, h.getDesc(), h.isInterface());
					}
				}
				return super.mapValue(value);
			}
		};
		ClassWriter writer = new ClassWriter(0);
		new ClassReader(candidate).accept(new ClassRemapper(writer, remapper), 0);
		return writer.toByteArray();
	}

	/**
	 * 按基线顺序重排方法/字段；新成员追加在末尾。返回是否发生重排。
	 *
	 * <p><b>枚举类不动字段表</b>：常量顺序就是 ordinal 语义，恢复基线顺序会把"重排/前插"从
	 * {@code ClassDiffUtil} 眼里抹掉，使 {@code CHANGED_ENUM_ORDER} 门失效。方法表照常对齐。</p>
	 */
	private static boolean orderMembers(ClassNode base, ClassNode cand, boolean enumClass) {
		boolean changed = false;
		Map<String, Integer> methodOrder = new LinkedHashMap<>();
		int i = 0;
		for (MethodNode m : base.methods) methodOrder.put(m.name + m.desc, i++);
		List<MethodNode> methods = new ArrayList<>(cand.methods);
		methods.sort((a, b) -> {
			Integer ia = methodOrder.get(a.name + a.desc);
			Integer ib = methodOrder.get(b.name + b.desc);
			if (ia != null && ib != null) return Integer.compare(ia, ib);
			if (ia != null) return -1;
			if (ib != null) return 1;
			return 0;
		});
		if (!sameOrder(cand.methods, methods)) {
			cand.methods = methods;
			changed = true;
		}

		if (enumClass) {
			// 字段表保持候选（新源码）顺序：枚举常量的相对位置是安全门要读的信号。
			return changed;
		}

		Map<String, Integer> fieldOrder = new LinkedHashMap<>();
		i = 0;
		for (FieldNode f : base.fields) fieldOrder.put(f.name + f.desc, i++);
		List<FieldNode> fields = new ArrayList<>(cand.fields);
		fields.sort((a, b) -> {
			Integer ia = fieldOrder.get(a.name + a.desc);
			Integer ib = fieldOrder.get(b.name + b.desc);
			if (ia != null && ib != null) return Integer.compare(ia, ib);
			if (ia != null) return -1;
			if (ib != null) return 1;
			return 0;
		});
		if (!sameOrder(cand.fields, fields)) {
			cand.fields = fields;
			changed = true;
		}
		return changed;
	}

	private static <T> boolean sameOrder(List<T> a, List<T> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i) != b.get(i)) return false;
		}
		return true;
	}

	private static boolean isGenerated(String name) {
		return name != null && GENERATED.matcher(name).matches();
	}

	/** {@code handler$zce000$cfg$foo} -> {@code handler}。 */
	private static String keyType(String name) {
		int first = name.indexOf('$');
		return first < 0 ? name : name.substring(0, first);
	}

	/** {@code handler$zce000$cfg$foo} -> {@code cfg$foo}；没有第二段 {@code $} 时返回全名。 */
	private static String tail(String name) {
		int first = name.indexOf('$');
		if (first < 0) return name;
		int second = name.indexOf('$', first + 1);
		return second < 0 ? name : name.substring(second + 1);
	}
}

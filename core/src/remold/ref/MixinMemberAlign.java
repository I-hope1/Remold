package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

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
		public final List<String> missingFromCandidate;   // 基线有、候选重映射后仍没有的生成成员
		public final List<String> addedInCandidate;       // 候选新增（基线没有）的生成成员

		Result(byte[] bytes, int renamed, boolean reordered,
		       List<String> missingFromCandidate, List<String> addedInCandidate) {
			this.bytes                = bytes;
			this.renamed              = renamed;
			this.reordered            = reordered;
			this.missingFromCandidate = Collections.unmodifiableList(missingFromCandidate);
			this.addedInCandidate     = Collections.unmodifiableList(addedInCandidate);
		}

		public boolean changed() {
			return renamed > 0 || reordered;
		}
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
		ClassNode base = read(baseline);
		ClassNode cand = read(candidate);
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

		ClassNode aligned = read(remapped);
		boolean reordered = orderMembers(base, aligned);
		byte[] out = reordered ? write(aligned) : remapped;

		List<String> missing = new ArrayList<>();
		List<String> added   = new ArrayList<>();
		Set<String> candidateGenerated = new HashSet<>();
		Set<String> baselineGenerated  = new HashSet<>();
		for (MethodNode m : aligned.methods) {
			if (isGenerated(m.name)) candidateGenerated.add(m.name + m.desc);
		}
		for (MethodNode m : base.methods) {
			if (!isGenerated(m.name)) continue;
			baselineGenerated.add(m.name + m.desc);
			if (!candidateGenerated.contains(m.name + m.desc)) missing.add(m.name + m.desc);
		}
		for (MethodNode m : aligned.methods) {
			if (isGenerated(m.name) && !baselineGenerated.contains(m.name + m.desc)) {
				added.add(m.name + m.desc);
			}
		}
		return new Result(out, renamed, reordered, missing, added);
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

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
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

	/** 按基线顺序重排方法/字段；新成员追加在末尾。返回是否发生重排。 */
	private static boolean orderMembers(ClassNode base, ClassNode cand) {
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

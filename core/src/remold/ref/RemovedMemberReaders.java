package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 删除成员时的「批次外读者」分析（{@code docs/status.md} 风险 11 / 风险 12，
 * 判据推导见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
 *
 * <p>热更删掉一个字段或方法后，任何仍持有对该成员引用的类，会在<b>未来任意一次执行</b>到该引用点时抛
 * {@code LinkageError}（{@code NoSuchFieldError} / {@code NoSuchMethodError}）——抛点在热更事务之外，
 * 无法回滚也无法归因。本类回答的问题是：<b>批次落地之后，还有哪些引用会悬空？</b></p>
 *
 * <h2>为什么必须同时给"改动前"和"改动后"两套类型世界</h2>
 * <p>直觉上会写成"解析结果落在被删成员上就算读者"，但这条在删除之后<b>永远不成立</b>：成员已经没了，
 * 解析当然落不到它。真正的判据要分两步：</p>
 * <ol>
 *   <li><b>改动前</b>解析这个引用，确认它<b>本来指的是</b>被删成员——这一步负责归属，避免把
 *       "指向别的类的同名字段"算进来；</li>
 *   <li><b>改动后</b>再解析一次，若<b>解析不到</b>，说明这个引用悬空了，它就是要找的读者。</li>
 * </ol>
 * <p>只做第一步会把"改动后已经被子类或接口重新接住"的引用误报成读者；只做第二步则无法归属到具体成员。</p>
 *
 * <h2>解析顺序按 JVMS，字段与方法相反</h2>
 * <ul>
 *   <li><b>字段</b>（JVMS 5.4.3.2）：自身 → <b>父接口（递归）</b> → 父类（递归）。父接口在父类
 *       <b>之前</b>，所以接口里的同名字段会截胡父类字段（实测见 §13.6.2）。</li>
 *   <li><b>方法</b>（JVMS 5.4.3.3 / 5.4.3.4）：自身 → 父类链 → 父接口（递归）。与字段相反。</li>
 * </ul>
 *
 * <h2>private 成员不需要解析</h2>
 * <p>private 成员不被继承（JLS §6.6），读者必须写成 {@code ((A) s).x}，强转后静态类型就是 {@code A}，
 * 所以它的 {@code Fieldref} owner <b>恒为声明类</b>。这类成员直接按 owner 相等判定。</p>
 *
 * <h2>三种结果，不能把"判不了"当成"没有"</h2>
 * <p>解析可能因为类型信息缺失而无法进行。把它当成"没有读者"是本类最危险的失败模式，因此单独记为
 * {@link Result#inconclusive()}；另有 {@link Result#retargeted()} 记录"改动后指向了别处"的引用
 * （典型是接口字段截胡），它不会抛 {@code LinkageError}，但会静默读到另一个成员。</p>
 *
 * <h2>扫描范围由调用方决定</h2>
 * <p>本类只分析传进来的字节码，不做任何 IO。"能发现多少读者"完全取决于喂了哪些类：只喂已加载类会
 * 漏掉尚未加载的陈旧读者。档位与代价见 {@code docs/initfix/07-p05-probe-results.md} §13.6.4。</p>
 *
 * <h2>不做的事</h2>
 * <ul>
 *   <li><b>不折算 pre-11 的合成访问器</b>：删 private 字段会让 {@code access$NNN} 桥消失或<b>改指向</b>
 *       另一个成员（编号顺移），后者在 diff 里表现为"同名同描述、方法体变了"，没有删除可查。
 *       本类只按传入的 {@code removed} 集合判定（§13.6.5 记录了判据）。</li>
 *   <li><b>不判断成员可访问性</b>：{@code private} 以外的修饰符不影响判定，解析本身已决定引用指向谁。</li>
 *   <li><b>不区分 classloader</b>：以内部类名为唯一 key，同名异加载器的类会被合并。</li>
 * </ul>
 */
public final class RemovedMemberReaders {

	/** 一个被删除的成员。{@code owner} / {@code name} / {@code desc} 均为字节码形态（owner 用内部名）。 */
	public record RemovedMember(String owner, String name, String desc, boolean isField, boolean isPrivate) {
		public RemovedMember {
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(desc, "desc");
		}

		/** 调试用的一行表示。 */
		public String key() {
			return owner + "." + name + desc;
		}
	}

	/**
	 * 一处命中。
	 * @param member   被删的成员
	 * @param reader   持有该引用的类（内部名）
	 * @param refOwner 引用在常量池里的 owner，可能与 {@code member.owner()} 不同
	 * @param kind     {@code field} / {@code method} / {@code interfaceMethod}
	 */
	public record Hit(RemovedMember member, String reader, String refOwner, String kind) { }

	/**
	 * 分析结果。
	 * @param hits         改动后会悬空的引用（真正的读者）
	 * @param inconclusive 归属对得上、但类型信息不足无法判定
	 * @param retargeted   改动前指向被删成员、改动后指向了别处（不抛错，但读到的东西变了）
	 */
	public record Result(List<Hit> hits, List<String> inconclusive, List<String> retargeted) {
		public Result {
			hits = List.copyOf(hits);
			inconclusive = List.copyOf(inconclusive);
			retargeted = List.copyOf(retargeted);
		}

		public boolean hasReaders() {
			return !hits.isEmpty();
		}
	}

	/** 类型的解析相关声明。{@code fields} / {@code methods} 的元素是 {@code name:desc}。 */
	public record TypeInfo(String superName, List<String> interfaces, Set<String> fields, Set<String> methods) {
		public TypeInfo {
			interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
			fields = fields == null ? Set.of() : Set.copyOf(fields);
			methods = methods == null ? Set.of() : Set.copyOf(methods);
		}
	}

	/** 解析结果的三种状态。 */
	public enum State {
		/** 找到了声明类。 */
		FOUND,
		/** 沿继承链走完仍未找到——引用悬空。 */
		NOT_FOUND,
		/** 路径上出现未提供的类型，判不了。 */
		UNKNOWN
	}

	/** @param declaring 仅当 {@code state == FOUND} 时非空 */
	public record Resolved(State state, String declaring) {
		static Resolved found(String declaring) { return new Resolved(State.FOUND, declaring); }
		static final Resolved NOT_FOUND = new Resolved(State.NOT_FOUND, null);
		static final Resolved UNKNOWN   = new Resolved(State.UNKNOWN, null);
	}

	/** 常量池里的一处成员引用。 */
	public record Ref(String kind, String owner, String name, String desc) { }

	private static final String KIND_FIELD = "field";
	private static final String KIND_METHOD = "method";
	private static final String KIND_INTERFACE_METHOD = "interfaceMethod";

	/**
	 * {@code java.lang.Object} 的字段集恒为空、方法集固定，调用方不必提供。缺失时按"已知但无声明"处理，
	 * 否则每次解析走到继承链顶端都会变成 {@link State#UNKNOWN}。
	 */
	private static final TypeInfo OBJECT = new TypeInfo(null, List.of(), Set.of(), Set.of());

	private RemovedMemberReaders() { }

	// ------------------------------------------------------------------------------------------------
	// 类型信息与引用
	// ------------------------------------------------------------------------------------------------

	/** 提取一个类的解析相关声明；只读结构，跳过代码与调试信息。 */
	public static TypeInfo describe(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes)
		 .accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Set<String> fields = new LinkedHashSet<>();
		if (cn.fields != null) {
			for (FieldNode f : cn.fields) fields.add(f.name + ":" + f.desc);
		}
		Set<String> methods = new LinkedHashSet<>();
		if (cn.methods != null) {
			for (MethodNode m : cn.methods) methods.add(m.name + ":" + m.desc);
		}
		return new TypeInfo(cn.superName, cn.interfaces, fields, methods);
	}

	/** 由 {@code 内部类名 -> 字节码} 构建解析用的类型世界。 */
	public static Map<String, TypeInfo> universe(Map<String, byte[]> classes) {
		Map<String, TypeInfo> u = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			u.put(e.getKey(), describe(e.getValue()));
		}
		return u;
	}

	/**
	 * 扫出一个类里所有可能"读某个成员"的引用。
	 *
	 * <p><b>只扫常量池，不进方法体</b>。理由是 {@code CONSTANT_MethodHandle_info}（tag 15）的
	 * {@code reference_index} 在规范上<b>必须</b>指向同一个常量池里的
	 * {@code Fieldref}(9) / {@code Methodref}(10) / {@code InterfaceMethodref}(11)
	 * （JVMS §4.4.8）。因此：</p>
	 * <ul>
	 *   <li>{@code GETFIELD} / {@code PUTFIELD} / {@code GETSTATIC} / {@code PUTSTATIC} 与
	 *       {@code invoke*} 引用的就是 9/10/11；</li>
	 *   <li>{@code LDC} / {@code LDC_W} 装的方法句柄、{@code invokedynamic} 引导参数里的方法引用
	 *       （{@code A::foo}、lambda 目标）都以 tag 15 存在，而 15 必然回指 9/10/11。</li>
	 * </ul>
	 * <p>所以扫一遍 9/10/11 就<b>一网打尽</b>，不需要再走 {@code MethodVisitor}。副作用是快：
	 * 解析完常量池即可返回，跳过字段表、方法表、{@code Code}、行号表与 {@code StackMapTable}
	 * ——这些占了 class 文件体积的绝大部分。</p>
	 *
	 * <p><b>代价是它是超集</b>：常量池里可能存在"没有任何指令引用"的条目（某些编译器会留下）。
	 * 这种条目永远不会执行，因此不会抛 {@code LinkageError}。对门控来说这是<b>保守方向</b>
	 * （宁可多拦），但要知道它会比"精确到指令"多报。若将来需要精确判定，可在本方法命中之后
	 * 再对该类做一次方法体精扫——两级漏斗，成本只落在真正命中的少数类上。</p>
	 *
	 * @throws IllegalArgumentException 常量池结构无法解析（调用方应把它记为"判不了"，不能当成"没有引用"）
	 */
	public static List<Ref> refs(byte[] classBytes) {
		int count = u2(classBytes, 8);
		int p = 10;
		byte[] tag = new byte[count];
		String[] utf8 = new String[count];
		int[] classNameIndex = new int[count];   // tag 7  -> name_index
		int[] refClassIndex = new int[count];    // tag 9/10/11 -> class_index
		int[] refNameTypeIndex = new int[count]; // tag 9/10/11 -> name_and_type_index
		int[] nameTypeName = new int[count];     // tag 12 -> name_index
		int[] nameTypeDesc = new int[count];     // tag 12 -> descriptor_index

		for (int i = 1; i < count; i++) {
			int t = classBytes[p++] & 0xFF;
			tag[i] = (byte) t;
			switch (t) {
				case 1 -> {                       // Utf8
					int len = u2(classBytes, p);
					p += 2;
					utf8[i] = decodeModifiedUtf8(classBytes, p, len);
					p += len;
				}
				case 3, 4 -> p += 4;              // Integer / Float
				case 5, 6 -> {                    // Long / Double 占两个槽位
					p += 8;
					i++;
				}
				case 7 -> {                       // Class
					classNameIndex[i] = u2(classBytes, p);
					p += 2;
				}
				case 8 -> p += 2;                 // String
				case 9, 10, 11 -> {               // Fieldref / Methodref / InterfaceMethodref
					refClassIndex[i] = u2(classBytes, p);
					refNameTypeIndex[i] = u2(classBytes, p + 2);
					p += 4;
				}
				case 12 -> {                      // NameAndType
					nameTypeName[i] = u2(classBytes, p);
					nameTypeDesc[i] = u2(classBytes, p + 2);
					p += 4;
				}
				case 15 -> p += 3;                // MethodHandle：reference_kind + reference_index
				case 16 -> p += 2;                // MethodType
				case 17, 18 -> p += 4;            // Dynamic / InvokeDynamic
				case 19, 20 -> p += 2;            // Module / Package
				default -> throw new IllegalArgumentException("unsupported constant pool tag " + t + " at #" + i);
			}
		}

		Set<Ref> out = new LinkedHashSet<>();
		for (int i = 1; i < count; i++) {
			int t = tag[i] & 0xFF;
			if (t != 9 && t != 10 && t != 11) continue;
			int nameType = refNameTypeIndex[i];
			String owner = utf8[classNameIndex[refClassIndex[i]]];
			String name = utf8[nameTypeName[nameType]];
			String desc = utf8[nameTypeDesc[nameType]];
			if (owner == null || name == null || desc == null) {
				throw new IllegalArgumentException("dangling constant pool reference at #" + i);
			}
			out.add(new Ref(t == 9 ? KIND_FIELD : t == 11 ? KIND_INTERFACE_METHOD : KIND_METHOD,
			                owner, name, desc));
		}
		return new ArrayList<>(out);
	}

	private static int u2(byte[] b, int offset) {
		if (offset < 0 || offset + 1 >= b.length) {
			throw new IllegalArgumentException("truncated class file at " + offset);
		}
		return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
	}

	/**
	 * 解码 {@code CONSTANT_Utf8} 的 modified UTF-8。
	 *
	 * <p>与标准 UTF-8 的差别只有两处：{@code NUL} 编成 {@code C0 80}，增补字符按代理对拆成两个三字节序列。
	 * 这里两种都按"逐字节还原成 char"处理，正好得到 Java 字符串语义。</p>
	 */
	private static String decodeModifiedUtf8(byte[] b, int offset, int length) {
		char[] chars = new char[length];
		int n = 0;
		int i = offset;
		int end = offset + length;
		while (i < end) {
			int c = b[i++] & 0xFF;
			if (c < 0x80) {
				chars[n++] = (char) c;
			} else if ((c & 0xE0) == 0xC0 && i < end) {
				chars[n++] = (char) (((c & 0x1F) << 6) | (b[i++] & 0x3F));
			} else if ((c & 0xF0) == 0xE0 && i + 1 < end) {
				int c2 = b[i++] & 0x3F;
				int c3 = b[i++] & 0x3F;
				chars[n++] = (char) (((c & 0x0F) << 12) | (c2 << 6) | c3);
			} else {
				throw new IllegalArgumentException("malformed modified UTF-8 at " + (i - 1));
			}
		}
		return new String(chars, 0, n);
	}

	// ------------------------------------------------------------------------------------------------
	// 解析
	// ------------------------------------------------------------------------------------------------

	/** JVMS 5.4.3.2 字段解析：自身 → 父接口（递归） → 父类（递归）。 */
	public static Resolved resolveField(Map<String, TypeInfo> world, String start, String name, String desc) {
		String key = name + ":" + desc;
		Set<String> visited = new HashSet<>();
		String c = start;
		while (c != null) {
			if (!visited.add(c)) return Resolved.NOT_FOUND;
			TypeInfo t = typeOf(world, c);
			if (t == null) return Resolved.UNKNOWN;
			if (t.fields().contains(key)) return Resolved.found(c);
			Resolved viaInterface = declaringInInterfaces(world, t.interfaces(), key, false, new HashSet<>());
			if (viaInterface != null) return viaInterface;
			c = t.superName();
		}
		return Resolved.NOT_FOUND;
	}

	/** JVMS 5.4.3.3 / 5.4.3.4 方法解析：自身 → 父类链 → 父接口（递归）。 */
	public static Resolved resolveMethod(Map<String, TypeInfo> world, String start, String name, String desc) {
		String key = name + ":" + desc;
		Set<String> visited = new HashSet<>();
		List<String> roots = new ArrayList<>();
		String c = start;
		while (c != null) {
			if (!visited.add(c)) break;
			TypeInfo t = typeOf(world, c);
			if (t == null) return Resolved.UNKNOWN;
			if (t.methods().contains(key)) return Resolved.found(c);
			roots.addAll(t.interfaces());
			c = t.superName();
		}
		Resolved viaInterface = declaringInInterfaces(world, roots, key, true, new HashSet<>());
		return viaInterface == null ? Resolved.NOT_FOUND : viaInterface;
	}

	private static TypeInfo typeOf(Map<String, TypeInfo> world, String internalName) {
		TypeInfo t = world.get(internalName);
		if (t != null) return t;
		return "java/lang/Object".equals(internalName) ? OBJECT : null;
	}

	private static Resolved declaringInInterfaces(Map<String, TypeInfo> world, List<String> interfaces,
	                                              String key, boolean method, Set<String> visited) {
		if (interfaces == null) return null;
		for (String itf : interfaces) {
			if (!visited.add(itf)) continue;
			TypeInfo t = typeOf(world, itf);
			if (t == null) return Resolved.UNKNOWN;
			if ((method ? t.methods() : t.fields()).contains(key)) return Resolved.found(itf);
			Resolved nested = declaringInInterfaces(world, t.interfaces(), key, method, visited);
			if (nested != null) return nested;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// 主入口
	// ------------------------------------------------------------------------------------------------

	/**
	 * 找出"批次落地后引用会悬空"的类。
	 *
	 * @param preWorld        <b>改动前</b>的类型世界，用于确认引用本来指向被删成员（归属）
	 * @param postWorld       <b>改动后</b>的类型世界，用于确认引用是否还解析得到（悬空判定）
	 * @param postChangeBytes 每个类<b>批次落地后</b>会运行的字节码：本批内的类给<b>新</b>字节码，
	 *                        批次外的类给<b>当前</b>字节码。传错会得到假阳性或假阴性
	 * @param removed         被删成员；{@code isPrivate} 为真时跳过解析，直接按 owner 判定
	 */
	public static Result find(Map<String, TypeInfo> preWorld,
	                          Map<String, TypeInfo> postWorld,
	                          Map<String, byte[]> postChangeBytes,
	                          Collection<RemovedMember> removed) {
		List<Hit> hits = new ArrayList<>();
		Set<String> inconclusive = new LinkedHashSet<>();
		Set<String> retargeted = new LinkedHashSet<>();

		Map<String, List<RemovedMember>> byKey = new LinkedHashMap<>();
		for (RemovedMember m : removed) {
			byKey.computeIfAbsent(m.name() + ":" + m.desc(), k -> new ArrayList<>()).add(m);
		}
		if (byKey.isEmpty()) return new Result(List.of(), List.of(), List.of());

		for (Map.Entry<String, byte[]> e : postChangeBytes.entrySet()) {
			String reader = e.getKey();
			List<Ref> classRefs;
			try {
				classRefs = refs(e.getValue());
			} catch (RuntimeException parseFailure) {
				// 解析不了就不能断言"没有引用"——把危险当成安全是本类最不该犯的错。
				inconclusive.add(reader + " (unparseable: " + parseFailure.getMessage() + ")");
				continue;
			}
			for (Ref ref : classRefs) {
				List<RemovedMember> candidates = byKey.get(ref.name() + ":" + ref.desc());
				if (candidates == null) continue;
				boolean refIsField = KIND_FIELD.equals(ref.kind());
				for (RemovedMember m : candidates) {
					if (m.isField() != refIsField) continue;
					String where = reader + " -> " + ref.owner() + "." + ref.name() + ref.desc();

					if (m.isPrivate()) {
						// private 成员的 Fieldref owner 恒为声明类（JLS §6.6 + 强转），不需要解析。
						if (ref.owner().equals(m.owner())) hits.add(new Hit(m, reader, ref.owner(), ref.kind()));
						continue;
					}

					Resolved before = resolve(preWorld, ref, m.isField());
					if (before.state() == State.UNKNOWN) {
						inconclusive.add(where + " (pre)");
						continue;
					}
					if (before.state() != State.FOUND || !before.declaring().equals(m.owner())) {
						continue;   // 这个引用本来指的就不是它
					}

					Resolved after = resolve(postWorld, ref, m.isField());
					switch (after.state()) {
						case NOT_FOUND -> hits.add(new Hit(m, reader, ref.owner(), ref.kind()));
						case UNKNOWN   -> inconclusive.add(where + " (post)");
						case FOUND     -> {
							if (!after.declaring().equals(m.owner())) {
								retargeted.add(where + " -> now " + after.declaring());
							}
						}
					}
				}
			}
		}
		return new Result(hits, new ArrayList<>(inconclusive), new ArrayList<>(retargeted));
	}

	private static Resolved resolve(Map<String, TypeInfo> world, Ref ref, boolean isField) {
		return isField
		 ? resolveField(world, ref.owner(), ref.name(), ref.desc())
		 : resolveMethod(world, ref.owner(), ref.name(), ref.desc());
	}
}

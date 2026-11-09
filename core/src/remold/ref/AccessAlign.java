package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重织入产物的<b>访问权限单调对齐</b>（Access Monotonicity Alignment）。
 *
 * <p><b>为什么需要它</b>：类在首次加载时经过的不止 Mixin —— 平台在 Mixin <b>之前</b>还会做
 * 访问权限变换：Fabric dev 的 {@code PackageAccessFixer}（Yarn named 下把所有非 private 成员
 * 提升为 public）、Fabric {@code AccessWidener}、Forge/NeoForge {@code AccessTransformer}。
 * 而 Remold 的 Mixin 目标重织入是从<b>原始 jar/磁盘字节</b>重新织的，这些前置变换不会被重放；
 * 若直接送去 {@code redefineClasses}，JBR 的增强重定义会把已加载子类拉进本次事务并
 * {@code link_class/verify}，一旦依赖这些被拓宽成员的跨包子类字节码仍按旧权限验证，就会当场
 * {@code VerifyError} → {@code JVMTI_ERROR_INTERNAL}，并在失败回滚后的半残 VM 上引发后续崩溃。</p>
 *
 * <p><b>判据（只放宽、不收紧）</b>：以 JVM 已加载的运行时字节码为基线，对同名同描述的字段与方法：</p>
 * <ol>
 *   <li>基线为 {@code public} 的成员，产物必须保持 {@code public}；</li>
 *   <li>基线为 {@code protected} 的成员，产物不得退化为 package-private / {@code private}；</li>
 *   <li>基线已剥离 {@code ACC_FINAL} 的类/字段/方法，产物必须同样剥离；</li>
 *   <li>类头按同一规则处理；产物新增的成员（基线中不存在）不受约束。</li>
 * </ol>
 *
 * <p>本类是纯函数：只读入参字节，不依赖反射 / 类加载 / 全局状态。产物保持与入参相同的常量池、
 * 方法体、StackMapTable（使用 {@code ClassReader} + {@code ClassWriter(reader, 0)} 原样搬运，
 * 不做帧重算）。</p>
 */
public final class AccessAlign {

	private AccessAlign() {}

	/** 对齐结果。{@code narrowed} 非空表示对齐后仍存在权限收窄（正常应为空，作为 fail-closed 的复核）。 */
	public static final class Result {
		public final byte[]       bytes;
		public final int          adjusted;
		public final List<String> narrowed;

		Result(byte[] bytes, int adjusted, List<String> narrowed) {
			this.bytes    = bytes;
			this.adjusted = adjusted;
			this.narrowed = Collections.unmodifiableList(narrowed);
		}

		public boolean changed() {
			return adjusted > 0;
		}
	}

	/**
	 * 把 {@code candidate}（重织入产物）按 {@code baseline}（JVM 已加载的运行时字节码）做单调对齐。
	 *
	 * @throws IllegalArgumentException 任一侧不可解析、或两者的内部类名不一致（无法安全对齐）
	 */
	public static Result align(byte[] candidate, byte[] baseline) {
		if (candidate == null || baseline == null) {
			throw new IllegalArgumentException("candidate/baseline must not be null");
		}
		Model base = parse(baseline);
		Model cand = parse(candidate);
		if (base.className == null || cand.className == null
		    || !base.className.equals(cand.className)) {
			throw new IllegalArgumentException("class name mismatch: baseline=" + base.className
				+ ", candidate=" + cand.className);
		}

		int[] adjustedClass = new int[1];
		int[] adjustedMembers = new int[1];
		ClassWriter writer = new ClassWriter(new ClassReader(candidate), 0);
		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visit(int version, int access, String name, String signature,
			                  String superName, String[] interfaces) {
				int aligned = alignClassAccess(access, base.access, adjustedClass);
				super.visit(version, aligned, name, signature, superName, interfaces);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor,
			                               String signature, Object value) {
				int aligned = alignMemberAccess(access, base.fields.get(name + ":" + descriptor), adjustedMembers);
				return super.visitField(aligned, name, descriptor, signature, value);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				int aligned = alignMemberAccess(access, base.methods.get(name + descriptor), adjustedMembers);
				return super.visitMethod(aligned, name, descriptor, signature, exceptions);
			}
		};
		new ClassReader(candidate).accept(visitor, 0);
		byte[] alignedBytes = writer.toByteArray();

		List<String> narrowed = narrowed(alignedBytes, base);
		return new Result(alignedBytes, adjustedClass[0] + adjustedMembers[0], narrowed);
	}

	/** 只做检查不改写：返回 {@code candidate} 中相对 {@code baseline} 访问权限收窄的成员（供自检门复核）。 */
	public static List<String> narrowed(byte[] candidate, byte[] baseline) {
		Model base = parse(baseline);
		return narrowed(candidate, base);
	}

	private static List<String> narrowed(byte[] candidate, Model base) {
		Model cand = parse(candidate);
		List<String> out = new ArrayList<>();
		if (cand.access != 0 && visibilityRank(cand.access) < visibilityRank(base.access)) {
			out.add("class " + base.className);
		}
		for (Map.Entry<String, Integer> e : cand.fields.entrySet()) {
			Integer b = base.fields.get(e.getKey());
			if (b != null && visibilityRank(e.getValue()) < visibilityRank(b)) {
				out.add("field " + base.className + "#" + e.getKey());
			}
		}
		for (Map.Entry<String, Integer> e : cand.methods.entrySet()) {
			Integer b = base.methods.get(e.getKey());
			if (b != null && visibilityRank(e.getValue()) < visibilityRank(b)) {
				out.add("method " + base.className + "#" + e.getKey());
			}
		}
		return out;
	}

	private static int alignClassAccess(int candidate, int baseline, int[] adjusted) {
		int aligned = applyFinal(applyVisibility(candidate, baseline), baseline);
		if (aligned != candidate) adjusted[0]++;
		return aligned;
	}

	private static int alignMemberAccess(int candidate, Integer baseline, int[] adjusted) {
		if (baseline == null) return candidate;   // 新增成员：无基线，不约束
		int aligned = applyFinal(applyVisibility(candidate, baseline), baseline);
		if (aligned != candidate) adjusted[0]++;
		return aligned;
	}

	private static int applyFinal(int candidate, int baseline) {
		if ((baseline & Opcodes.ACC_FINAL) == 0) return candidate & ~Opcodes.ACC_FINAL;
		return candidate;
	}

	private static int applyVisibility(int candidate, int baseline) {
		int baseRank = visibilityRank(baseline);
		if (visibilityRank(candidate) >= baseRank) return candidate;
		int cleared = candidate & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
		switch (baseRank) {
			case 3:  return cleared | Opcodes.ACC_PUBLIC;
			case 2:  return cleared | Opcodes.ACC_PROTECTED;
			case 1:  return cleared;                       // package-private
			default: return cleared | Opcodes.ACC_PRIVATE; // 基线 private，产物只会更窄，不会走到这里
		}
	}

	private static int visibilityRank(int access) {
		if ((access & Opcodes.ACC_PUBLIC) != 0) return 3;
		if ((access & Opcodes.ACC_PROTECTED) != 0) return 2;
		if ((access & Opcodes.ACC_PRIVATE) != 0) return 0;
		return 1;
	}

	private static Model parse(byte[] bytes) {
		Model model = new Model();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature,
			                  String superName, String[] interfaces) {
				model.className = name;
				model.access    = access;
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor,
			                               String signature, Object value) {
				model.fields.put(name + ":" + descriptor, access);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				model.methods.put(name + descriptor, access);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return model;
	}

	private static final class Model {
		String className;
		int access;
		final Map<String, Integer> fields  = new LinkedHashMap<>();
		final Map<String, Integer> methods = new LinkedHashMap<>();
	}
}

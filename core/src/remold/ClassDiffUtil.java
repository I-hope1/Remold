package remold;

import remold.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.*;

/**
 * 类差异分析工具
 * 用于检测类文件的变更，支持字段、方法的增删改检测
 * 主要用于热重载系统的智能更新决策
 *
 * <p><b>设计文档与状态索引</b>：
 * 设计文档参见 {@code docs/topology/07-layout-gate-and-risks.md} §1 与 {@code docs/initfix/03-runtime-driver.md} §5；
 * 实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 * <pre>
 * 核心功能：
 * 1. 比较两个版本的类字节码差异
 * 2. 检测类继承关系变更（父类、接口）
 * 3. 检测字段的增删变化
 * 4. 检测方法的增删改变化
 * 5. 提供详细的变更日志输出
 * </pre>
 */
public final class ClassDiffUtil {

	/**
	 * 比较上下文类
	 * 封装了一次比较操作所需的所有临时数据结构
	 * 使用对象复用模式提升性能
	 */
	private static class ComparisonContext {
		/** 线程本地变量，存储比较上下文，避免重复创建对象 */
		static final ThreadLocal<ComparisonContext> CONTEXT       = ThreadLocal.withInitial(ComparisonContext::new);
		/** 旧版本接口映射表：hash -> 接口全限定名 */
		final        LongObjectMap<String>
		                                            oldInterfaces = new LongObjectMap<>(),
		/** 新版本接口映射表：hash -> 接口全限定名 */
		newInterfaces = new LongObjectMap<>();

		/** 字段映射表：复合hash(字段名+描述符) -> 字段名 */
		final LongObjectMap<String>     fieldMap  = new LongObjectMap<>(256);
		/** 方法映射表：复合hash(方法名+描述符) -> MethodNode */
		final LongObjectMap<MethodNode> methodMap = new LongObjectMap<>(256);
		/** 差异结果对象 */
		final ClassDiff                 result    = new ClassDiff();

		/**
		 * 重置上下文状态，为下一次比较做准备
		 * 清空所有映射表和结果集
		 */
		void reset() {
			oldInterfaces.clear();
			newInterfaces.clear();

			fieldMap.clear();
			methodMap.clear();
			result.reset();
		}
	}


	/**
	 * 比较两个字节码数组的差异
	 * @param oldBytes 旧版本字节码数组
	 * @param newBytes 新版本字节码数组
	 * @return 差异结果对象，请勿缓存使用（每次调用都返回新的实例）
	 */
	public static ClassDiff diff(byte[] oldBytes, byte[] newBytes) {
		ClassNode oldNode = parse(oldBytes);
		ClassNode newNode = parse(newBytes);
		return diff(oldNode, newNode);
	}

	/**
	 * 层次变更是否必须拒绝（纯判决，供批量路径与动态路径共用）。
	 *
	 * <p>判决表：</p>
	 * <ul>
	 *   <li><b>父类变化</b>：一律拒绝。增强重定义对 superclass 的支持没有本地实证，
	 *       放行只会把失败推迟到 link/verify 阶段（失败形态是 {@code InternalError}/{@code VerifyError}，
	 *       会污染后续所有重定义）。</li>
	 *   <li><b>仅接口增删</b>：标准 HotSpot 拒绝（JVM 的 {@code redefineClasses} 不允许改接口表）；
	 *       增强重定义（JBR/DCEVM，{@code -XX:+AllowEnhancedClassRedefinition} 生效）放行——
	 *       接口表是类元数据的一部分，DCEVM 支持改写它；Mixin Duck 接口热更依赖这条。</li>
	 *   <li><b>无层次变更</b>：放行。</li>
	 * </ul>
	 *
	 * @param diff     类差异
	 * @param enhanced 当前 JVM 是否具备增强重定义能力（{@code EnvironmentProbe}）
	 * @return {@code true} 表示必须拒绝该类的重定义
	 */
	public static boolean mustRejectHierarchyChange(ClassDiff diff, boolean enhanced) {
		if (diff == null || !diff.hierarchyChanged) {
			return false;
		}
		if (diff.superChanged) {
			return true;
		}
		// 走到这里只可能是接口增删（diff() 只在这两种情况下置 hierarchyChanged）。
		return !enhanced;
	}

	/**
	 * 层次变更的可读描述（日志用）。
	 *
	 * @param diff 类差异
	 * @return 例如 {@code "superclass A -> B; interfaces +[x/Y] -[z/W]"}
	 */
	public static String describeHierarchyChange(ClassDiff diff) {
		if (diff == null || !diff.hierarchyChanged) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		if (diff.superChanged) {
			sb.append("superclass ").append(diff.oldClass == null ? "?" : diff.oldClass.superName)
				.append(" -> ").append(diff.newClass == null ? "?" : diff.newClass.superName);
		}
		if (!diff.addedInterfaces.isEmpty() || !diff.removedInterfaces.isEmpty()) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append("interfaces +").append(diff.addedInterfaces)
				.append(" -").append(diff.removedInterfaces);
		}
		return sb.length() == 0 ? "unknown hierarchy change" : sb.toString();
	}

	/**
	 * 注解默认值（{@code AnnotationDefault} 属性）变化的比对（纯函数，<b>fail-closed</b>）。
	 *
	 * <p><b>为什么需要单独比对</b>：{@link ClassDiff} 只描述结构（字段/方法/层次），注解成员默认值
	 * 变化不会出现在任何差异集合里；而 JVM 探针读数（JBR 21 / JBR 25，标准 HotSpot 与
	 * {@code -XX:+AllowEnhancedClassRedefinition}）显示：改掉注解类型的默认值后
	 * {@code redefineClasses} 会成功，但<b>已经加载</b>的消费者类仍读到<b>旧默认值</b>
	 * （{@code getAnnotation(...).value()} 全档一致），且标准 HotSpot 上连重定义之后才加载的
	 * 消费者也读到旧值。因此这类变化一旦放过去，就是"静默按旧行为走"。</p>
	 *
	 * <p><b>判据边界</b>：只比较<b>两侧都存在</b>的注解成员（{@code name + desc} 相同）的
	 * {@code AnnotationDefault}。成员的增删属于结构变化，由层次门与 JVM 自身的能力判据负责，
	 * 不在这里重复判定。</p>
	 *
	 * <p><b>fail-closed</b>：字节码解析失败时返回不可判定原因（调用方据此拒绝），
	 * 而不是当作"没有变化"放行。</p>
	 *
	 * @param oldBytes 已加载的基线字节（可为 {@code null} = 无基线，返回 {@code null} 不判定）
	 * @param newBytes 候选字节
	 * @return {@code null} = 没有注解默认值变化（或不是注解类型）；否则为可读的变化描述
	 */
	public static String describeAnnotationDefaultChange(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null || newBytes == null) {
			return null;
		}
		ClassNode oldNode;
		ClassNode newNode;
		try {
			oldNode = parse(oldBytes);
			newNode = parse(newBytes);
		} catch (Throwable t) {
			return "cannot verify AnnotationDefault (unparseable class bytes: "
				+ t.getClass().getSimpleName() + ")";
		}
		boolean oldAnnotation = (oldNode.access & Opcodes.ACC_ANNOTATION) != 0;
		boolean newAnnotation = (newNode.access & Opcodes.ACC_ANNOTATION) != 0;
		if (!oldAnnotation && !newAnnotation) {
			return null;
		}
		if (oldNode.methods == null || newNode.methods == null) {
			return null;
		}
		Map<String, MethodNode> oldMembers = new HashMap<>();
		for (MethodNode method : oldNode.methods) {
			if (method.name == null || method.name.startsWith("<")) continue;
			oldMembers.put(method.name + method.desc, method);
		}
		List<String> changes = new ArrayList<>();
		for (MethodNode method : newNode.methods) {
			if (method.name == null || method.name.startsWith("<")) continue;
			MethodNode previous = oldMembers.get(method.name + method.desc);
			if (previous == null) continue;   // 成员新增：结构门负责
			if (!sameAnnotationDefault(previous.annotationDefault, method.annotationDefault)) {
				changes.add(method.name + "() " + renderAnnotationDefault(previous.annotationDefault)
					+ " -> " + renderAnnotationDefault(method.annotationDefault));
			}
		}
		if (changes.isEmpty()) {
			return null;
		}
		return String.join("; ", changes);
	}

	/** 默认值深比较：支持基本类型/包装、{@code String}、{@code Type}、嵌套注解与数组。 */
	private static boolean sameAnnotationDefault(Object left, Object right) {
		if (left == null || right == null) {
			return left == right;
		}
		if (left instanceof AnnotationNode || right instanceof AnnotationNode) {
			return bothAnnotationsEqual(left, right);
		}
		if (left instanceof List || right instanceof List) {
			if (!(left instanceof List) || !(right instanceof List)) return false;
			List<?> a = (List<?>) left;
			List<?> b = (List<?>) right;
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) {
				if (!sameAnnotationDefault(a.get(i), b.get(i))) return false;
			}
			return true;
		}
		return Objects.equals(left, right);
	}

	private static boolean bothAnnotationsEqual(Object left, Object right) {
		if (!(left instanceof AnnotationNode) || !(right instanceof AnnotationNode)) {
			return false;
		}
		AnnotationNode a = (AnnotationNode) left;
		AnnotationNode b = (AnnotationNode) right;
		if (!Objects.equals(a.desc, b.desc)) return false;
		List<Object> av = a.values == null ? Collections.emptyList() : a.values;
		List<Object> bv = b.values == null ? Collections.emptyList() : b.values;
		if (av.size() != bv.size()) return false;
		for (int i = 0; i + 1 < av.size(); i += 2) {
			if (!Objects.equals(av.get(i), bv.get(i))) return false;
			if (!sameAnnotationDefault(av.get(i + 1), bv.get(i + 1))) return false;
		}
		return true;
	}

	/** 默认值的可读形态（日志用）：{@code <none>} 表示该成员没有默认值。 */
	private static String renderAnnotationDefault(Object value) {
		if (value == null) {
			return "<none>";
		}
		if (value instanceof String) {
			return "\"" + value + "\"";
		}
		if (value instanceof Type) {
			return ((Type) value).getDescriptor();
		}
		if (value instanceof AnnotationNode) {
			AnnotationNode node = (AnnotationNode) value;
			return "@" + node.desc;
		}
		if (value instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object element : (List<?>) value) {
				if (!first) sb.append(",");
				sb.append(renderAnnotationDefault(element));
				first = false;
			}
			return sb.append("]").toString();
		}
		return String.valueOf(value);
	}

	/**
	 * 解析字节码为ASM树节点
	 * <pre>
	 * 性能优化说明：
	 * - SKIP_DEBUG：跳过调试信息（行号、局部变量表等），提升解析速度
	 * - SKIP_FRAMES：跳过栈帧信息，减少内存占用
	 * 这些优化对逻辑对比无影响，但显著提升性能
	 * @param bytes 字节码数组
	 * @return ASM ClassNode 对象
	 */
	private static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/**
	 * 类差异结果封装类
	 * 包含所有类型的变更信息
	 */
	public static final class ClassDiff {
		public ClassNode oldClass, newClass;
		/** 方法体被修改的方法列表：存储格式为 "methodName + methodDescriptor" */
		public final List<String> modifiedBodyMethods = new ArrayList<>();
		/** 新增的方法列表：存储格式为 "methodName + methodDescriptor" */
		public final List<String> addedMethods        = new ArrayList<>();
		/** 删除的方法列表：存储格式为 "methodName + methodDescriptor" */
		public final List<String> removedMethods      = new ArrayList<>();
		/**
		 * 字段变更列表：包括新增和删除的字段，存储格式为 "+ fieldName" 或 "- fieldName"。
		 *
		 * <p><b>只反映结构</b>：字段的键是 {@code name + desc}（外加 {@code static} 位），
		 * 所以<b>注解、初值表达式、{@code final}/{@code volatile} 等修饰符的改动都不会出现在这里</b>。
		 * 初值是否变了要由 {@code InitFix} 自己比切片；注解是否还在要由它自己扫新字节码
		 * （{@code docs/initfix/03-runtime-driver.md} §4）。</p>
		 *
		 * <p>注意"Diff 里没有变化"并不等于"这个类不会被热更"：是否 redefine 由上游按
		 * <b>字节哈希</b>判定（{@code RemoldAgent}），只改一个注解也会走 redefine。</p>
		 */
		public final List<String> changedFields       = new ArrayList<>();

		/** 用于分析新增的实例字段 */
		public final Set<String> addedInstanceFields = new HashSet<>();
		public final Set<String> addedStaticFields   = new HashSet<>();
		/** 类型发生变更的字段名集合（desc 变了但同名同 static 属性） */
		public final Set<String> typeChangedFields   = new HashSet<>();
		/** 标注了 @RemoldReinit 的字段名集合 */
		public final Set<String> reinitFields        = new HashSet<>();

		/** 继承层次是否发生变化（父类或接口变更） */
		public       boolean      hierarchyChanged = false;
		/** 父类是否变化（JBR/DCEVM 也不放行，必须重启） */
		public       boolean      superChanged     = false;
		/** 新增的接口内部名（父类未变时，接口增删是可判定的子集） */
		public final List<String> addedInterfaces  = new ArrayList<>();
		/** 删除的接口内部名 */
		public final List<String> removedInterfaces = new ArrayList<>();
		/** 错误信息列表：记录严重的不兼容变更 */
		public final List<String> errors           = new ArrayList<>();

		/**
		 * 重置所有集合，清空之前的比较结果
		 */
		public void reset() {
			oldClass = newClass = null;
			modifiedBodyMethods.clear();
			addedMethods.clear();
			removedMethods.clear();
			changedFields.clear();
			addedInstanceFields.clear();
			addedStaticFields.clear();
			typeChangedFields.clear();
			reinitFields.clear();
			hierarchyChanged = false;
			superChanged = false;
			addedInterfaces.clear();
			removedInterfaces.clear();
			errors.clear();
		}

		/**
		 * 是否只有接口表变化（父类未变）。
		 *
		 * @return {@code true} 表示层次变更全部来自接口增删
		 */
		public boolean interfaceChanged() {
			return !this.superChanged && (!this.addedInterfaces.isEmpty() || !this.removedInterfaces.isEmpty());
		}

		/**
		 * 检查是否存在任何变更
		 * @return true表示有变更，false表示无变更
		 */
		public boolean hasChange() {
			return hierarchyChanged || !(modifiedBodyMethods.isEmpty() && addedMethods.isEmpty() &&
			                             removedMethods.isEmpty() && changedFields.isEmpty() &&
			                             errors.isEmpty());
		}

		/**
		 * 检查是否存在结构性变更（影响类结构的变更）
		 * 结构性变更包括：方法增删、字段增删
		 * @return true表示有结构性变更
		 */
		public boolean structureChanged() {
			return !(addedMethods.isEmpty() && removedMethods.isEmpty() && changedFields.isEmpty());
		}
	}

	/** 哈希键提取函数：将字符串转换为long哈希值 */
	static ToLongFunction<String>   keyExtractor = Utils::hash;
	/** 值映射函数：恒等映射 */
	static Function<String, String> valueMapper  = i -> i;

	/**
	 * 比较两个类节点的差异
	 * 核心算法采用"映射销毁法"：
	 * 1. 先将旧版本元素全部放入映射表
	 * 2. 遍历新版本元素，在映射表中查找匹配项
	 * 3. 找到则移除，未找到则是新增
	 * 4. 映射表中剩余的就是被删除的元素
	 * @param oldC 旧版本类节点
	 * @param newC 新版本类节点
	 * @return 差异结果
	 */
	private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
		ComparisonContext ctx = ComparisonContext.CONTEXT.get();
		ctx.reset();

		ClassDiff d = ctx.result;
		d.oldClass = oldC;
		d.newClass = newC;

		// 检查父类是否变更
		if (!Objects.equals(oldC.superName, newC.superName)) {
			d.hierarchyChanged = true;
			d.superChanged = true;
			d.errors.add("! CRITICAL: Superclass changed: " + oldC.superName + " -> " + newC.superName);
		}

		// 检查接口变更
		var oldInterfacesMap = ctx.oldInterfaces;
		oldInterfacesMap.putAll(oldC.interfaces, keyExtractor, valueMapper);
		var newInterfacesMap = ctx.newInterfaces;
		newInterfacesMap.putAll(newC.interfaces, keyExtractor, valueMapper);

		// 比较接口集合是否相等
		if (!Objects.equals(oldInterfacesMap, newInterfacesMap)) {
			d.hierarchyChanged = true;
			d.errors.add("! CRITICAL: Interfaces changed");

			// 详细记录具体变更
			for (String itf : newC.interfaces) {
				// 如果在旧接口中找不到，则是新增接口
				if (oldInterfacesMap.remove(Utils.hash(itf)) == null) {
					d.errors.add("+ " + itf);
					d.addedInterfaces.add(itf);
					d.hierarchyChanged = true;
				}
			}
			// 剩余的旧接口就是被删除的
			oldInterfacesMap.forEachValue(itf -> {
				d.errors.add("- " + itf);
				d.removedInterfaces.add(itf);
				d.hierarchyChanged = true;
			});
		}

		// 字段对比：检测字段的增删变化
		var oldFieldsMap = ctx.fieldMap;
		Map<String, FieldDescInfo> oldFieldInfoMap = new HashMap<>(oldC.fields.size());
		// 将旧版本所有字段放入映射表，key为字段名和描述符的复合哈希
		for (FieldNode f : oldC.fields) {
			if (isInternalMarkerField(f)) continue;
			boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;
			oldFieldsMap.put(Utils.compositeHash(f.name, f.desc), (isStatic ? "*" : "") + f.name);
			oldFieldInfoMap.put(f.name, new FieldDescInfo(f.desc, isStatic));
		}

		// 遍历新版本字段
		for (FieldNode newF : newC.fields) {
			if (isInternalMarkerField(newF)) continue;
			if (hasRemoldReinit(newF)) {
				d.reinitFields.add(newF.name);
			}
			long    key         = Utils.compositeHash(newF.name, newF.desc);
			String  oldVal      = oldFieldsMap.remove(key);
			boolean newIsStatic = (newF.access & Opcodes.ACC_STATIC) != 0;

			// 如果旧映射表中没有此key，说明是新增字段（或改了描述符/改了静态性）
			if (oldVal == null) {
				d.changedFields.add("+ " + (newIsStatic ? "*" : "") + newF.name);

				(newIsStatic ? d.addedStaticFields : d.addedInstanceFields).add(newF.name);
				FieldDescInfo oldInfo = oldFieldInfoMap.get(newF.name);
				if (oldInfo != null && !oldInfo.desc.equals(newF.desc) && oldInfo.isStatic == newIsStatic) {
					d.typeChangedFields.add(newF.name);
				}
			} else {
				boolean oldIsStatic = oldVal.startsWith("*");
				if (oldIsStatic != newIsStatic) {
					String cleanName = oldIsStatic ? oldVal.substring(1) : oldVal;
					// 判定为：旧字段删除，新字段新增
					d.changedFields.add("- " + (oldIsStatic ? "*" : "") + cleanName);
					d.changedFields.add("+ " + (newIsStatic ? "*" : "") + newF.name);

					(newIsStatic ? d.addedStaticFields : d.addedInstanceFields).add(newF.name);
					// d.errors.add("! CRITICAL: Field static modifier changed: " + cleanName);
				}
			}
		}
		// 映射表中剩余的就是被删除的字段
		oldFieldsMap.forEachValue(removedFieldName -> {
			d.changedFields.add("- " + removedFieldName);
		});

		// 方法对比逻辑：同样采用映射销毁法
		var oldMethods = ctx.methodMap;
		// 将旧版本所有方法放入映射表，key为方法名和描述符的复合哈希
		for (MethodNode m : oldC.methods) {
			oldMethods.put(Utils.compositeHash(m.name, m.desc), m);
		}

		// 遍历新版本方法
		for (MethodNode newM : newC.methods) {
			long       key  = Utils.compositeHash(newM.name, newM.desc);
			MethodNode oldM = oldMethods.remove(key);

			if (oldM == null) {
				// 新增方法：记录方法名+描述符
				d.addedMethods.add(newM.name + newM.desc);
			} else if (isCodeChanged(oldM, newM)) {
				// 方法存在但代码发生了变化
				d.modifiedBodyMethods.add(newM.name + newM.desc);
			}
		}
		// 映射表中剩余的就是被删除的方法
		oldMethods.forEachValue(m -> {
			d.removedMethods.add(m.name + m.desc);
		});

		return d;
	}

	/**
	 * 内部合成标记字段过滤（参考 {@code docs/initfix/03-runtime-driver.md} §5）。
	 *
	 * <p>热更管线自己往类里塞的字段不是业务变更，必须从 Diff 里剔除，否则会被
	 * {@code InitFix} 当成"新增字段"去做字段初始化补丁：</p>
	 * <ul>
	 *   <li><b>{@code $remold$} 前缀</b>：{@code AnnotationTransformer.forceStaticLambdas}
	 *       的幂等标记字段 {@code $remold$lambdasForced}；</li>
	 *   <li><b>{@code ACC_SYNTHETIC}</b>：javac / Kotlin 生成的
	 *       {@code this$0}、{@code $stable} 之类内部字段。</li>
	 * </ul>
	 *
	 * <p>对 {@code oldClass} 与 {@code newClass} 两侧对称过滤，因此这类字段既不进
	 * {@code changedFields}，也不进 {@code addedInstanceFields}/{@code addedStaticFields}，
	 * 同时不会让 {@link ClassDiff#structureChanged()} 出现假阳性。</p>
	 */
	private record FieldDescInfo(String desc, boolean isStatic) {}

	private static boolean hasRemoldReinit(FieldNode f) {
		if (f == null) return false;
		if (hasRemoldReinitAnnotation(f.visibleAnnotations)) return true;
		return hasRemoldReinitAnnotation(f.invisibleAnnotations);
	}

	private static boolean hasRemoldReinitAnnotation(List<AnnotationNode> annotations) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) {
			if ("Lremold/annotation/RemoldReinit;".equals(a.desc)) return true;
		}
		return false;
	}

	private static boolean isInternalMarkerField(FieldNode f) {
		return (f.access & Opcodes.ACC_SYNTHETIC) != 0
		       || (f.name != null && f.name.startsWith("$remold$"));
	}

	/**
	 * 检测方法代码是否发生变化
	 * @param m1 旧版本方法节点
	 * @param m2 新版本方法节点
	 * @return true表示代码有变化，false表示代码相同
	 * <p>
	 * 检测策略：
	 * 1. 首先快速检查访问权限是否改变（不同则肯定有变化）
	 * 2. 然后计算两个方法的逻辑指纹进行精确对比
	 */
	private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
		// 快速判断：访问权限不同则肯定有变化
		// 这是一个重要的早期退出条件，避免不必要的复杂计算
		if (m1.access != m2.access) return true;

		// 计算逻辑指纹进行精确对比
		// 通过指令流特征生成哈希值来判断代码是否实质改变
		return calculateMethodHash(m1) != calculateMethodHash(m2);
	}

	/**
	 * 计算方法的逻辑指纹哈希值
	 * <pre>
	 * 实现原理：
	 * 使用 MethodFingerprinter 访问者模式遍历方法的所有指令，
	 * 提取关键特征（如操作码、常量引用等）生成唯一的哈希值。
	 * 相同逻辑的方法会产生相同的哈希值，不同的实现会产生不同哈希值。
	 * @param mn 方法节点
	 * @return 该方法的哈希指纹
	 */
	public static long calculateMethodHash(MethodNode mn) {
		MethodFingerprinter fingerprinter = MethodFingerprinter.CONTEXT.get();
		fingerprinter.reset();
		mn.accept(fingerprinter);
		return fingerprinter.getHash();
	}

	/**
	 * 输出类差异日志
	 * @param className 类的全限定名
	 * @param diff      差异结果对象
	 *                  <p>
	 *                  日志格式示例：
	 *                  [DIFF] com.example.MyClass:
	 *                  !!!Errors:   [! CRITICAL: Superclass changed: java.lang.Object -> java.util.ArrayList]
	 *                  *Modified: [method1()V, method2(I)Z]
	 *                  +Added:    [newMethod()V]
	 *                  -Removed:  [oldMethod()V]
	 *                  !Fields:   [+ newField, - oldField]
	 */
	static void logDiff(String className, ClassDiffUtil.ClassDiff diff) {
		RemoldAgent.log(className + " is " + (diff.hasChange() ? "Changed" : "Unchanged"));
		// 如果没有任何变更，直接返回不输出日志
		if (!diff.hasChange()) return;

		// 预估StringBuilder容量以提升性能
		// [DIFF] (7) + className (平均30字符) + 4行内容 (平均200字符) ≈ 256
		StringBuilder sb = new StringBuilder(256);
		sb.append("[DIFF] ").append(className).append(":\n");

		// 依次追加各种变更类型的信息
		appendIfNotEmpty(sb, " !!!Errors: ", diff.errors);
		appendIfNotEmpty(sb, " *Modified: ", diff.modifiedBodyMethods);
		appendIfNotEmpty(sb, " +Added:    ", diff.addedMethods);
		appendIfNotEmpty(sb, " -Removed:  ", diff.removedMethods);
		appendIfNotEmpty(sb, " !Fields:   ", diff.changedFields);

		// 通过RemoldAgent输出日志
		RemoldAgent.info(sb.toString());
	}

	/** 系统换行符常量，避免重复调用System.lineSeparator() */
	private static final String NE = System.lineSeparator();

	/**
	 * 辅助方法：如果集合非空则追加到StringBuilder
	 * @param sb    StringBuilder对象
	 * @param label 标签前缀
	 * @param items 要追加的集合
	 */
	private static void appendIfNotEmpty(StringBuilder sb, String label, Collection<?> items) {
		if (items != null && !items.isEmpty()) {
			sb.append(label).append(items).append(NE);
		}
	}
}

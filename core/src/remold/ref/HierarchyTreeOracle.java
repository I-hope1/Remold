package remold.ref;

import remold.WorkspaceRawIndex;
import remold.AnnotationTransformer;
import remold.BytecodeSources;
import remold.RemoldAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * 基于纯字节码元数据与 Agent 缓存实现的 {@link ClassHierarchyOracle}。
 *
 * <p>底层依赖 {@link AnnotationTransformer.HierarchyTree} 维护的轻量继承图（仅保留 {@code superName}、
 * {@code interfaces} 与 {@code access}），并优先从 {@link RemoldAgent#bytecodeCache}、
 * {@link BytecodeSources} 登记口（织入期生成的合成类）与 ClassLoader 资源流中读取字节码。
 * 在整个查询过程中绝不触发类加载。</p>
 *
 * <p>单次分析实例中通过局部 Map 缓存解析的 {@link ClassNode}，并在 Nest 成员证明中
 * 采用流式 ASM Visitor 提取 {@code PUTFIELD}/{@code PUTSTATIC} 指令，实现高效的不可变性证明。</p>
 */
public final class HierarchyTreeOracle implements ClassHierarchyOracle {
	private final WeakReference<ClassLoader> loader;
	private final ClassNode                  preferredClass;
	private final Map<String, ClassNode>     resolved     = new HashMap<>();
	private final Map<String, ClassNode>     nestResolved = new HashMap<>();

	public HierarchyTreeOracle(ClassLoader loader) {
		this(loader, null);
	}

	public HierarchyTreeOracle(ClassLoader loader, ClassNode preferredClass) {
		this.loader = new WeakReference<>(loader);
		this.preferredClass = preferredClass;
		if (preferredClass != null) {
			AnnotationTransformer.HierarchyTree.register(preferredClass);
		}
	}

	@Override
	public HierarchyCheck checkAssignableFrom(String superType, String subType) {
		return AnnotationTransformer.HierarchyTree.checkAssignableFrom(superType, subType, loader.get());
	}

	@Override
	public boolean isAssignableFrom(String superType, String subType) {
		return AnnotationTransformer.HierarchyTree.isAssignableFrom(superType, subType, loader.get());
	}

	@Override
	public boolean isInterface(String className) {
		if (preferredClass != null && preferredClass.name.equals(className)) {
			return (preferredClass.access & Opcodes.ACC_INTERFACE) != 0;
		}
		return AnnotationTransformer.HierarchyTree.isInterface(className, loader.get());
	}

	@Override
	public int getClassModifiers(String className) {
		ClassNode node = resolve(className);
		if (node == null) throw new IllegalArgumentException("class metadata unavailable: " + className);
		return node.access;
	}

	@Override
	public Optional<Integer> getFieldModifiers(String className, String fieldName, String desc) {
		return findField(className, className, fieldName, desc, new HashSet<>()).map(member -> member.access);
	}

	/**
	 * Resolve an inherited field down to its declaration and return the full {@link FieldNode}
	 * (needed for annotations such as {@code @RemoldReinit}). Returns empty when the field cannot
	 * be resolved or is not visible from {@code lookupType}.
	 */
	public Optional<FieldNode> findFieldNode(String lookupType, String name, String desc) {
		Optional<MemberRef> ref = findField(lookupType, lookupType, name, desc, new HashSet<>());
		if (!ref.isPresent()) return Optional.empty();
		ClassNode declaring = resolve(ref.get().declaringClass);
		if (declaring == null || declaring.fields == null) return Optional.empty();
		for (FieldNode field : declaring.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return Optional.of(field);
		}
		return Optional.empty();
	}

	/**
	 * 新版本的 {@code className} 是否遮蔽了父类链上声明的实例字段（字段遮蔽）。
	 *
	 * <p>DCEVM 给子类新增字段时，按 {@code (name, desc)} 把父类同名字段的槽位值复制进新槽，
	 * <b>不区分访问权限</b>：父类 {@code private} 字段同样会把非零值带进来。因此这里只按声明匹配，
	 * 不走 {@link #findField} 的可见性过滤（那条路径用于解析"从本类可见的成员"）。</p>
	 *
	 * <p>只沿 {@code superName} 链向上：接口字段一定是 {@code static final}，不占实例槽位，
	 * 不构成遮蔽。</p>
	 *
	 * @return 命中的祖先类内部名；没有命中、或父类链任一环取不到字节码时返回 empty
	 *         （解析不到就不能断言"不是遮蔽"，fail-closed 交回既有的条件 CAS 行为）
	 */
	public Optional<String> findInstanceFieldDeclaredInSuperclasses(
	 String className, String fieldName, String fieldDesc) {
		ClassNode node = resolve(className);
		if (node == null) return Optional.empty();
		Set<String> visited = new HashSet<>();
		String      current = node.superName;
		while (current != null && visited.add(current)) {
			ClassNode parent = resolve(current);
			if (parent == null) return Optional.empty();
			if (parent.fields != null) {
				for (FieldNode field : parent.fields) {
					if (!field.name.equals(fieldName) || !field.desc.equals(fieldDesc)) continue;
					if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
					return Optional.of(current);
				}
			}
			current = parent.superName;
		}
		return Optional.empty();
	}

	/**
	 * 新版本的 {@code className} 是否遮蔽了父类链上声明的<b>静态</b>字段。
	 *
	 * <p>与实例字段分开：静态字段的槽位复制行为由 DCEVM 实现决定，必须实测（见
	 * {@code ShadowFieldProbe}）；未验证前不得把它当成实例字段同款处理。</p>
	 */
	public Optional<String> findStaticFieldDeclaredInSuperclasses(
	 String className, String fieldName, String fieldDesc) {
		ClassNode node = resolve(className);
		if (node == null) return Optional.empty();
		Set<String> visited = new HashSet<>();
		String      current = node.superName;
		while (current != null && visited.add(current)) {
			ClassNode parent = resolve(current);
			if (parent == null) return Optional.empty();
			if (parent.fields != null) {
				for (FieldNode field : parent.fields) {
					if (!field.name.equals(fieldName) || !field.desc.equals(fieldDesc)) continue;
					if ((field.access & Opcodes.ACC_STATIC) == 0) continue;
					return Optional.of(current);
				}
			}
			current = parent.superName;
		}
		return Optional.empty();
	}

	@Override
	public Optional<Integer> getMethodModifiers(String className, String methodName, String desc) {
		return resolveMember(className, methodName, desc, false).map(member -> member.access);
	}

	@Override
	public Optional<MemberRef> resolveMember(String className, String name, String desc, boolean isField) {
		if (isField) return findField(className, className, name, desc, new HashSet<>());
		if ("<init>".equals(name)) return declaredMethod(className, name, desc);

		Set<String> classChain = new LinkedHashSet<>();
		String      current    = className;
		while (current != null && classChain.add(current)) {
			ClassNode node = resolve(current);
			if (node == null) return Optional.empty();
			Optional<MemberRef> declared = declaredMethod(current, name, desc);
			if (declared.isPresent() && visibleFromLookup(className, current, declared.get().access)) return declared;
			current = node.superName;
		}

		Set<String> visitedInterfaces = new HashSet<>();
		for (String classNameInChain : classChain) {
			ClassNode node = resolve(classNameInChain);
			if (node == null || node.interfaces == null) continue;
			for (String itf : node.interfaces) {
				Optional<MemberRef> found = findInterfaceMethod(className, itf, name, desc, visitedInterfaces);
				if (found.isPresent()) return found;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<InheritedField> findInheritedField(String className, String fieldName, String desc) {
		ClassNode node = resolve(className);
		if (node == null) return Optional.empty();
		return findInheritedField(className, node.superName, fieldName, desc, new HashSet<>());
	}

	/**
	 * 在祖先链上按 {@code superclass -> interfaces -> superclass'} 的顺序找第一条同名同描述符声明，
	 * <b>不</b>跳过不可见成员：可读性作为结果字段返回，由调用方决定 fail-closed。
	 */
	private Optional<InheritedField> findInheritedField(
	 String lookupType, String currentType, String name, String desc, Set<String> visited) {
		if (currentType == null || !visited.add(currentType)) return Optional.empty();
		ClassNode node = resolve(currentType);
		if (node == null) return Optional.empty();
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) {
				return Optional.of(new InheritedField(
				 currentType, field.access, visibleFromLookup(lookupType, currentType, field.access)));
			}
		}
		if (node.interfaces != null) {
			for (String itf : node.interfaces) {
				Optional<InheritedField> found = findInheritedField(lookupType, itf, name, desc, visited);
				if (found.isPresent()) return found;
			}
		}
		return findInheritedField(lookupType, node.superName, name, desc, visited);
	}

	private Optional<MemberRef> findField(String lookupType, String currentType, String name, String desc,
	                                      Set<String> visited) {
		if (!visited.add(currentType)) return Optional.empty();
		ClassNode node = resolve(currentType);
		if (node == null) return Optional.empty();
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)
			    && visibleFromLookup(lookupType, currentType, field.access)) {
				return Optional.of(new MemberRef(currentType, field.access));
			}
		}
		if (node.interfaces != null) {
			for (String itf : node.interfaces) {
				Optional<MemberRef> found = findField(lookupType, itf, name, desc, visited);
				if (found.isPresent()) return found;
			}
		}
		return node.superName == null
		 ? Optional.empty()
		 : findField(lookupType, node.superName, name, desc, visited);
	}

	private Optional<MemberRef> findInterfaceMethod(String lookupType, String interfaceType, String name, String desc,
	                                                Set<String> visited) {
		if (!visited.add(interfaceType)) return Optional.empty();
		ClassNode node = resolve(interfaceType);
		if (node == null) return Optional.empty();
		Optional<MemberRef> declared = declaredMethod(interfaceType, name, desc);
		if (declared.isPresent() && visibleFromLookup(lookupType, interfaceType, declared.get().access)) return declared;
		if (node.interfaces != null) {
			for (String parent : node.interfaces) {
				Optional<MemberRef> found = findInterfaceMethod(lookupType, parent, name, desc, visited);
				if (found.isPresent()) return found;
			}
		}
		return Optional.empty();
	}

	private Optional<MemberRef> declaredMethod(String className, String name, String desc) {
		ClassNode node = resolve(className);
		if (node == null) return Optional.empty();
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) {
				return Optional.of(new MemberRef(className, method.access));
			}
		}
		return Optional.empty();
	}

	private boolean visibleFromLookup(String lookupType, String declaringType, int access) {
		if (lookupType.equals(declaringType)) return true;
		if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
		if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) return true;
		return packageName(lookupType).equals(packageName(declaringType));
	}

	private static String packageName(String internalName) {
		int separator = internalName.lastIndexOf('/');
		return separator < 0 ? "" : internalName.substring(0, separator);
	}

	@Override
	public List<ClassNode> getNestMembers(String className) {
		ClassNode member = resolveNestNode(className);
		if (member == null) throw new IllegalStateException("nest class metadata unavailable: " + className);
		String    hostName = member.nestHostClass == null ? member.name : member.nestHostClass;
		ClassNode host     = resolveNestNode(hostName);
		if (host == null) throw new IllegalStateException("nest host metadata unavailable: " + hostName);

		Map<String, ClassNode> members = new LinkedHashMap<>();
		members.put(host.name, host);
		if (host.nestMembers != null) {
			for (String nestMember : host.nestMembers) {
				ClassNode node = resolveNestNode(nestMember);
				if (node == null) {
					throw new IllegalStateException("nest member metadata unavailable: " + nestMember);
				}
				members.put(nestMember, node);
			}
		}
		if (!members.containsKey(member.name)) members.put(member.name, member);
		return new ArrayList<>(members.values());
	}

	@Override
	public List<NestFieldWrite> getNestFieldWrites(String className) {
		List<ClassNode>      members = getNestMembers(className);
		List<NestFieldWrite> writes  = new ArrayList<>();
		for (ClassNode member : members) {
			if (member == preferredClass) {
				for (MethodNode method : member.methods) {
					for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
						if (insn instanceof FieldInsnNode field && isPut(field.getOpcode())) {
							writes.add(new NestFieldWrite(member.name, method.name, method.desc, field));
						}
					}
				}
			} else {
				byte[] bytes = readClassBytes(member.name);
				if (bytes == null) throw new IllegalStateException("nest member bytecode unavailable: " + member.name);
				try {
					new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public MethodVisitor visitMethod(int access, String methodName, String methodDesc,
						                                 String signature, String[] exceptions) {
							return new MethodVisitor(Opcodes.ASM9) {
								@Override
								public void visitFieldInsn(int opcode, String owner, String name, String desc) {
									if (isPut(opcode)) {
										writes.add(new NestFieldWrite(member.name, methodName, methodDesc,
										 new FieldInsnNode(opcode, owner, name, desc)));
									}
								}
							};
						}
					}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				} catch (Throwable failure) {
					throw new IllegalStateException("cannot scan nest member bytecode: " + member.name, failure);
				}
			}
		}
		return writes;
	}

	private static boolean isPut(int opcode) {
		return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
	}

	private ClassNode resolveNestNode(String internalName) {
		if (preferredClass != null && preferredClass.name.equals(internalName)) return preferredClass;
		ClassNode known = nestResolved.get(internalName);
		if (known != null) return known;
		byte[] bytes = readClassBytes(internalName);
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node,
			 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			nestResolved.put(internalName, node);
			return node;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private ClassNode resolve(String internalName) {
		if (preferredClass != null && preferredClass.name.equals(internalName)) return preferredClass;
		ClassNode known = resolved.get(internalName);
		if (known != null) return known;
		byte[] bytes = readClassBytes(internalName);
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node,
			 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			resolved.put(internalName, node);
			return node;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private byte[] readClassBytes(String internalName) {
		ClassLoader targetLoader = loader.get();
		byte[] batch = InitFix.batchClassBytes(targetLoader, internalName);
		if (batch != null) return batch;
		if (InitFix.batchViewActive(targetLoader) && InitFix.batchCandidateMissing(targetLoader, internalName)) return null;
		byte[] cached = RemoldAgent.getRuntimeBytecode(internalName.replace('/', '.'));
		if (cached != null) return cached;
		// 织入期生成的合成类（Mixin 的 Target$Anonymous$<uuid>）磁盘上没有 .class、也不经过
		// Remold 的 transformer，只能由生成方回灌到登记口。
		byte[] supplied = WorkspaceRawIndex.lookup(internalName);
		if (supplied != null) return supplied;
		ClassLoader sourceLoader = targetLoader == null ? ClassLoader.getSystemClassLoader() : targetLoader;
		if (sourceLoader == null) return null;
		try (InputStream in = sourceLoader.getResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * 已登记的 {@code 继承图} 中 {@code internalName} 的全部子类型（内部名，不含自身）。
	 *
	 * <p>见 {@link AnnotationTransformer.HierarchyTree#knownSubtypes(String, ClassLoader)} 的完备性边界：
	 * 有实例的子类必已登记，因此该集合可用于"作用域是否覆盖到会写该字段的子类实例"这类判定。</p>
	 */
	public Set<String> knownSubtypes(String internalName) {
		return AnnotationTransformer.HierarchyTree.knownSubtypes(internalName, loader.get());
	}

	/** 与层级解析同源的字节读取口（缓存 → 生成类登记口 → 类加载器资源流），只读、不触发类加载。 */
	public byte[] classBytes(String internalName) {
		return readClassBytes(internalName);
	}
}

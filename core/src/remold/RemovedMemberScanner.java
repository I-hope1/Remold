package remold;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import remold.RemoldAgent.RemovedMemberScan;
import remold.ref.RemovedMemberReaders;

import static remold.RemoldAgent.bytecodeCache;
import static remold.RemoldAgent.dotted;
import static remold.RemoldAgent.exitInternalRedefine;
import static remold.RemoldAgent.fetchOriginalBytecode;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.inst;
import static remold.RemoldAgent.internalName;
import static remold.RemoldAgent.loadedClassesMap;
import static remold.RemoldAgent.warn;

/**
 * "被删成员在批次外还有没有读者"的扫描与判定（判据见
 * {@code docs/initfix/07-p05-probe-results.md} §13.6）。
 *
 * <h2>字节源：已加载的类只能读内存</h2>
 * <p>已加载且不在本批的类，其字节码只从 {@link RemoldAgent#bytecodeCache} 取——那是 JVM 里当前生效的那份。
 * <b>严禁</b>回退去读磁盘构建目录：批次撕裂时（bulk 重定义失败后退回逐类、个别类失败被吞），
 * 磁盘上的 {@code Reader.class} 可能已经是重编后的新版本，而 JVM 里跑的仍是旧字节码；
 * 读磁盘会把真正的读者当成"已不再读"而漏掉。磁盘目录只用来补充<b>尚未加载</b>的工程类。</p>
 *
 * <p>取不到某个已加载工程类的内存字节时，本轮扫描判为<b>不完整</b>，整体退回存活实例判据
 * ——把"没扫全"当成"没有读者"是本条最危险的读法。</p>
 *
 * <h2>范围</h2>
 * <p>只覆盖工程自身的构建输出（由本批类的路径反推根目录）。JDK 与第三方 jar 不在范围内：
 * 那里的读者属于已知边界，不是本模块能解决的（见 {@code docs/status.md} 风险 12）。</p>
 *
 * <p><b>为什么独立成类</b>：本模块只做"读字节、解析常量池、归并判决"，判决结果交给
 * {@link RemovedMemberScan} 表达；它不执行任何布局动作，也不改批次。与"取动作、回滚事务"的接线
 * 分离后，扫描本身的成本与保守边界可以单独推演。</p>
 */
final class RemovedMemberScanner {

	private RemovedMemberScanner() { }

	/** 读者扫描只看工程自身的类；这些前缀是 JDK / 标准库，不可能引用工程字段。 */
	private static final String[] NON_PROJECT_PREFIXES = {
		"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "kotlin."
	};

	/** 读者扫描的类数上限，防止异常路径下把整个磁盘拖进来。 */
	private static final int MAX_READER_SCAN_CLASSES = 20_000;

	/**
	 * 扫描"批次外读者"：本批被删的实例字段，在批次落地之后还有谁解析得到它
	 * （判据见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
	 *
	 * @return 类名 → 该类被删字段的批次外读者；空 map 表示本轮不做判定（没有删除，或扫描不完整）
	 */
	static Map<String, RemovedMemberScan> scan(Map<String, byte[]> newBatchBytes,
	                                           Map<String, Path> classToPath) {
		long start = System.nanoTime();

		// ---- 1. 本批被删的**实例**字段（静态字段不参与，理由见 layout decision 的注释）----
		Map<String, List<RemovedMemberReaders.RemovedMember>> removedByClass = new LinkedHashMap<>();
		Map<String, byte[]> oldBatchBytes = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			String className = e.getKey();
			byte[] oldBytes = bytecodeCache.get(className);
			if (oldBytes == null) {
				Class<?> c = loadedClassesMap.get(className);
				if (c != null) {
					try {
						oldBytes = fetchOriginalBytecode(c);
					} catch (Throwable ignored) {
						// 取不到旧基线：这个类无法判定，下面会因为 removedByClass 为空而整体跳过
					}
				}
			}
			if (oldBytes == null) continue;
			oldBatchBytes.put(internalName(className), oldBytes);
			List<RemovedMemberReaders.RemovedMember> removed = removedMembers(oldBytes, e.getValue());
			if (!removed.isEmpty()) removedByClass.put(className, removed);
		}
		if (removedByClass.isEmpty()) return Map.of();

		// ---- 2. 读者字节码：本批用新字节，批次外已加载的工程类用内存字节，未加载的工程类读磁盘 ----
		Map<String, byte[]> post = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			post.put(internalName(e.getKey()), e.getValue());
		}

		Set<Path> roots = projectClassRoots(classToPath);
		List<Class<?>> missing = new ArrayList<>();
		for (Map.Entry<String, Class<?>> e : loadedClassesMap.entrySet()) {
			String name = e.getKey();
			if (newBatchBytes.containsKey(name)) continue;
			if (RemoldAgent.isNonLoadableClass(name)) continue;
			Class<?> clazz = e.getValue();
			// 数组类没有代码；hidden class（lambda 等）既不进 ClassFileTransformer 也不可重定义，
			// 它们引用的成员由宿主类的常量池覆盖。不滤掉这两类，uncached 会永远大于 0，
			// 判据等于永久失效（JBR 25 下 [Lp.Foo; 与 p.Foo$$Lambda/0x… 都会继承宿主的 CodeSource）。
			if (clazz.isArray() || clazz.isHidden()) continue;
			if (!inst.isModifiableClass(clazz)) continue;
			if (!isProjectClass(clazz, roots)) continue;
			byte[] bytes = bytecodeCache.get(name);
			if (bytes == null) {
				missing.add(clazz);
				continue;
			}
			post.putIfAbsent(internalName(name), bytes);
		}

		// Transformer 注册之前就已加载的漏网之鱼：一次批量 retransform 补齐（不要逐类调）。
		int uncached = 0;
		if (!missing.isEmpty()) {
			warmBytecodeCache(missing);
			for (Class<?> clazz : missing) {
				byte[] bytes = bytecodeCache.get(clazz.getName());
				if (bytes == null) {
					uncached++;
					continue;
				}
				post.putIfAbsent(internalName(clazz.getName()), bytes);
			}
		}
		if (uncached > 0) {
			warn("[LAYOUT-READERS] " + uncached + " loaded project class(es) have no cached bytecode;"
			     + " reader scan is inconclusive this round, falling back to the liveness check."
			     + " (Reading them from the build output would be wrong: after a torn batch the file"
			     + " may already be the new version while the JVM still runs the old one.)");
			return Map.of();
		}
		for (Path root : roots) collectClassFiles(root, post);

		// ---- 3. 判定 ----
		Map<String, byte[]> pre = new LinkedHashMap<>(post);
		pre.putAll(oldBatchBytes);
		List<RemovedMemberReaders.RemovedMember> allRemoved = new ArrayList<>();
		for (List<RemovedMemberReaders.RemovedMember> l : removedByClass.values()) allRemoved.addAll(l);

		// 先按"未扫描"铺好结果。扫描失败时也必须带着 removedMethods 标志返回：方法删除没有存活实例
		// 判据可以退，调用方若把"没扫成"读成"没读者"，删方法就会被静默放行。
		Map<String, RemovedMemberScan> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<RemovedMemberReaders.RemovedMember>> e : removedByClass.entrySet()) {
			List<RemovedMemberReaders.RemovedMember> list = e.getValue();
			out.put(e.getKey(), RemovedMemberScan.unscanned(
				list.stream().anyMatch(m -> !m.isField()),
				list.stream().anyMatch(m -> m.isField() && m.isStatic()),
				list.stream().filter(RemovedMemberReaders.RemovedMember::hasConstantValue)
				    .map(RemovedMemberReaders.RemovedMember::name).toList()));
		}

		RemovedMemberReaders.Result result;
		try {
			result = RemovedMemberReaders.find(RemovedMemberReaders.universe(pre),
			                                   RemovedMemberReaders.universe(post), post, allRemoved);
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] reader scan failed (" + t + "); falling back to the liveness check.");
			return out;
		}
		if (!result.inconclusive().isEmpty()) {
			warn("[LAYOUT-READERS] reader scan is inconclusive for " + result.inconclusive().size()
			     + " reference(s), e.g. " + result.inconclusive().get(0)
			     + "; falling back to the liveness check.");
			return out;
		}

		Map<String, List<String>> readersByClass = new LinkedHashMap<>();
		Map<String, List<String>> shiftedByClass = new LinkedHashMap<>();
		for (String className : removedByClass.keySet()) {
			readersByClass.put(className, new ArrayList<>());
			shiftedByClass.put(className, new ArrayList<>());
		}
		collectFindings(result.hits(), readersByClass);
		collectFindings(result.abstractMissing(), readersByClass);
		collectFindings(result.incompatibleAccess(), readersByClass);
		// 合法回退只记录、不阻断：删掉子类 Override 方法 / 遮蔽字段后，读者解析到父类实现或
		// 同静态性的父类字段。旧字节码本来就能正确解析（全量重编也一样），运行期零异常。
		collectFindings(result.dispatchShifted(), shiftedByClass);
		collectFindings(result.retargeted(), shiftedByClass);

		for (Map.Entry<String, RemovedMemberScan> e : out.entrySet()) {
			String className = e.getKey();
			RemovedMemberScan before = e.getValue();
			e.setValue(new RemovedMemberScan(true, readersByClass.get(className),
			                                 shiftedByClass.get(className), before.removedMethods(),
			                                 before.removedStaticFields(), before.constantFields()));
		}

		long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		info("[LAYOUT-READERS] scanned " + post.size() + " class(es) for removed members of "
		     + removedByClass.size() + " class(es) in " + ms + "ms; dangling=" + result.hits().size()
		     + ", abstract-gaps=" + result.abstractMissing().size()
		     + ", dispatch-shifts=" + result.dispatchShifted().size());
		return out;
	}

	/** 把命中的 reader 归到"被删成员所属的类"上。 */
	private static void collectFindings(List<RemovedMemberReaders.Hit> hits,
	                                    Map<String, List<String>> byClass) {
		for (RemovedMemberReaders.Hit hit : hits) {
			List<String> readers = byClass.get(dotted(hit.member().owner()));
			if (readers == null) continue;
			String reader = dotted(hit.reader());
			if (!readers.contains(reader)) readers.add(reader);
		}
	}

	/**
	 * 旧版里有、新版里没有的成员（实例字段 + 方法）。
	 *
	 * <p>合成标记字段不计（它们由对齐器负责）；{@code <init>} / {@code <clinit>} 不计——构造器不会被
	 * "删除后仍能工作"，删掉它们类本身就不可用了，那属于另一类问题。{@code $remold$} 前缀是热更管线
	 * 自己注入的，不算业务变更。</p>
	 *
	 * <p>方法与字段的差别在于<b>失败形态</b>：字段悬空抛 {@code NoSuchFieldError}；方法可能悬空
	 * （{@code NoSuchMethodError}），也可能被祖先实现静默接住（分派转移），或让具体类失去接口方法
	 * 的唯一实现（{@code AbstractMethodError}）。三者都由 {@link RemovedMemberReaders} 分类。</p>
	 */
	private static List<RemovedMemberReaders.RemovedMember> removedMembers(byte[] oldBytes,
	                                                                     byte[] newBytes) {
		ClassNode oldClass = new ClassNode();
		ClassNode newClass = new ClassNode();
		new ClassReader(oldBytes).accept(oldClass, ClassReader.SKIP_CODE);
		new ClassReader(newBytes).accept(newClass, ClassReader.SKIP_CODE);

		Set<String> fieldSurvivors = new HashSet<>();
		for (FieldNode f : newClass.fields) fieldSurvivors.add(f.name + ":" + f.desc);

		List<RemovedMemberReaders.RemovedMember> out = new ArrayList<>();
		for (FieldNode f : oldClass.fields) {
			if ((f.access & Opcodes.ACC_SYNTHETIC) != 0 || f.name.startsWith("$remold$")) continue;
			if (fieldSurvivors.contains(f.name + ":" + f.desc)) continue;
			out.add(new RemovedMemberReaders.RemovedMember(oldClass.name, f.name, f.desc, true,
				(f.access & Opcodes.ACC_PRIVATE) != 0,
				(f.access & Opcodes.ACC_STATIC) != 0,
				// ASM 把 ConstantValue 属性解析成 FieldNode.value。非 null 就是编译期常量，
				// javac 会把它内联进读者的 ldc/bipush——读者常量池里没有 Fieldref，扫描无从下手。
				f.value != null));
		}

		Set<String> methodSurvivors = new HashSet<>();
		for (MethodNode m : newClass.methods) methodSurvivors.add(m.name + ":" + m.desc);
		for (MethodNode m : oldClass.methods) {
			if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
			if (m.name.startsWith("$remold$")) continue;
			if (methodSurvivors.contains(m.name + ":" + m.desc)) continue;
			out.add(new RemovedMemberReaders.RemovedMember(oldClass.name, m.name, m.desc, false,
			                                               (m.access & Opcodes.ACC_PRIVATE) != 0,
			                                               (m.access & Opcodes.ACC_STATIC) != 0));
		}
		return out;
	}

	/**
	 * 由本批类的文件路径反推工程构建输出根目录（{@code .../build/classes/java/main} 之类）。
	 * 做法是把类名里的包段数从路径末尾去掉。
	 */
	private static Set<Path> projectClassRoots(Map<String, Path> classToPath) {
		Set<Path> roots = new LinkedHashSet<>();
		if (classToPath == null) return roots;
		for (Map.Entry<String, Path> e : classToPath.entrySet()) {
			Path p = e.getValue();
			if (p == null) continue;
			int depth = e.getKey().split("\\.").length;
			for (int i = 0; i < depth && p != null; i++) p = p.getParent();
			if (p != null) roots.add(p.toAbsolutePath().normalize());
		}
		return roots;
	}

	/** 该已加载类是否属于工程自身的构建输出（按 code source 位置判断，不触发 IO）。 */
	private static boolean isProjectClass(Class<?> c, Set<Path> roots) {
		if (roots.isEmpty()) return false;
		ClassLoader loader = c.getClassLoader();
		if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return false;
		String name = c.getName();
		for (String prefix : NON_PROJECT_PREFIXES) {
			if (name.startsWith(prefix)) return false;
		}
		try {
			java.security.CodeSource cs = c.getProtectionDomain() == null
			 ? null : c.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null) return false;
			Path location = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
			for (Path root : roots) {
				if (location.startsWith(root)) return true;
			}
		} catch (Throwable ignored) {
			// 拿不到位置就按"不是工程类"处理：范围外的读者是已知边界，不能因此判成"没有读者"。
		}
		return false;
	}

	/** 把构建输出里的 class 补进读者集合（只补尚未收录的：内存字节优先）。 */
	private static void collectClassFiles(Path root, Map<String, byte[]> into) {
		if (!Files.isDirectory(root)) return;
		try (var walk = Files.walk(root)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				if (into.size() > MAX_READER_SCAN_CLASSES) {
					warn("[LAYOUT-READERS] class file cap (" + MAX_READER_SCAN_CLASSES
					     + ") reached under " + root + "; reader scan may be incomplete.");
					return;
				}
				if (!p.toString().endsWith(".class")) continue;
				Path rel = root.relativize(p);
				String internal = rel.toString().replace('\\', '/');
				internal = internal.substring(0, internal.length() - ".class".length());
				// Mixin 等模板类不是可加载类，不能进入读者集合；
				// 否则它自己的字段引用可能被误算成"批次外读者"。
				if (RemoldAgent.isNonLoadableClass(internal.replace('/', '.'))) continue;
				if (into.containsKey(internal)) continue;
				try {
					into.put(internal, Files.readAllBytes(p));
				} catch (Throwable ignored) {
					// 单个文件读失败不影响整体；缺失的类会在解析期表现为 UNKNOWN 并被判为不完整。
				}
			}
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] cannot walk " + root + ": " + t);
		}
	}

	/**
	 * 一次批量 {@code retransformClasses} 把缺字节的类补进缓存。
	 *
	 * <p><b>必须批量</b>：{@code retransformClasses} 每次调用都是一次 safepoint，逐类调会把热更拖成
	 * 几百次停顿。这里只用一个捕获型 transformer，不改字节、不参与布局判定。</p>
	 */
	private static void warmBytecodeCache(List<Class<?>> classes) {
		if (classes.isEmpty()) return;
		Class<?>[] targets = classes.toArray(new Class<?>[0]);
		Map<String, byte[]> captured = new HashMap<>();
		ClassFileTransformer capture = new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain pd, byte[] buffer) {
				if (classBeingRedefined != null && className != null && buffer != null) {
					captured.put(className.replace('/', '.'), buffer);
				}
				return null;
			}
		};
		inst.addTransformer(capture, true);
		try {
			RemoldAgent.enterInternalRetransform(new LinkedHashSet<>(classes.stream().map(Class::getName).toList()));
			try {
				inst.retransformClasses(targets);
			} finally {
				exitInternalRedefine();
			}
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] bulk retransform for cache warm-up failed: " + t);
			return;
		} finally {
			inst.removeTransformer(capture);
		}
		captured.forEach(bytecodeCache::putIfAbsent);
	}
}

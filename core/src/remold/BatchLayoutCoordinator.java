package remold;

import remold.annotation.*;
import remold.evict.ReflectionCacheManager;
import remold.offset.LayoutPredictor;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner;
import remold.offset.SuperclassDriftGate;
import remold.profiler.DynamicProfilerAPI;
import remold.ref.HeapFieldWatch;
import remold.ref.InitFix;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.Ref;
import remold.ref.StageBInstanceCache;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.ChangesetLog;
import remold.vcs.VcsCommitGate;
import remold.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.*;

import static remold.MountManager.*;

import remold.RemoldAgent.AlignmentTransaction;
import remold.RemoldAgent.GateCachedDiff;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.RemoldAgent.RoundSkips;
import remold.vcs.LoadFrontier;

import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.DIFF_LOG;
import static remold.RemoldAgent.LAYOUT_GATE;
import static remold.RemoldAgent.LAYOUT_GATE_REJECTED;
import static remold.RemoldAgent.LAYOUT_GATE_WAIVED;
import static remold.RemoldAgent.LOCAL_CLASS_GUARD;
import static remold.RemoldAgent.LOCAL_CLASS_REJECTED;
import static remold.RemoldAgent.LOCAL_CLASS_WAIVED;
import static remold.RemoldAgent.OFFSET_STRICT;
import static remold.RemoldAgent.calculateHash;
import static remold.RemoldAgent.clearRejected;
import static remold.RemoldAgent.decideLayout;
import static remold.RemoldAgent.decideLocalClassGuard;
import static remold.RemoldAgent.dotted;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.fetchOriginalBytecode;
import static remold.RemoldAgent.fileDiskHashes;
import static remold.RemoldAgent.getRuntimeBytecode;
import static remold.RemoldAgent.hasLiveInstances;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.internalName;
import static remold.RemoldAgent.isBlacklisted;
import static remold.RemoldAgent.loadedClassesMap;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.noteRejected;
import static remold.RemoldAgent.recordRuntimeBytecode;
import static remold.RemoldAgent.setLivenessBatch;
import static remold.RemoldAgent.skipVerdictForLayout;
import static remold.RemoldAgent.warn;

/**
 * 批量热更布局门、局部类/枚举守卫与偏移扫描协调器（从 {@link RemoldAgent} 拆出）。
 */
final class BatchLayoutCoordinator {

	private BatchLayoutCoordinator() {}

	/**
	 * {@code docs/topology/04-tiers-and-rejection.md} §4 宿主级拒绝：把「宿主类 + 其下属全部匿名类」整体移出本批重定义。
	 *
	 * <p><b>为什么必须连新侧匿名类的原始类名一起移除</b>：一旦不做对齐，新编译产物的 {@code Foo$2}
	 * 与 JVM 中已加载的旧 {@code Foo$2} 同名但语义不同，把它送进 redefinition 就是把老实例的方法表
	 * 交给无关的新类 —— 正是本模块存在的理由。同理，宿主本身也必须一起移除，否则它的新字节码会
	 * 引用到没有被对齐过的 {@code Foo$N}。</p>
	 *
	 * <p>因此拒绝的语义是"这一组本轮完全不动"，与 {@code docs/topology/04-tiers-and-rejection.md} §4
	 * 的"以宿主 + 其下属全部匿名类为原子单元整体拒绝回滚"一致。</p>
	 * @param t 触发拒绝的异常；{@link AnonClassAligner.AlignmentRejectedException} 视为**预期**拒绝
	 *          （打 {@code [HOTSWAP-REJECT]} 告警），其它异常视为对齐器缺陷（打 error + 堆栈）
	 * @param skips 本轮摘要台账：宿主与整组匿名类都要登记，否则摘要里只剩"成功了几个"
	 */
	static void rejectHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                    String hostName, RoundSkips skips, Throwable t) {
		boolean expected = t instanceof AnonClassAligner.AlignmentRejectedException;
		String reason = expected
		 ? ((AnonClassAligner.AlignmentRejectedException) t).reason
		 : (t.getClass().getSimpleName() + ": " + t.getMessage());
		if (expected) {
			warn("[HOTSWAP-REJECT] Structural ambiguity detected in " + hostName
			     + ". Redefine skipped safely. Reason: " + reason
			     + ". Please hot-swap again or restart.");
		} else {
			error("[HOTSWAP-REJECT] Anonymous class alignment failed in " + hostName
			      + ". Redefine skipped safely. Reason: " + reason
			      + ". Please hot-swap again or restart.", t);
		}
		List<String> dropped = dropHostGroup(newBatchBytes, classToPath, hostName);
		skips.addGroup(dropped, hostName, RoundSkips.verdict("ANON-ALIGN-REJECT", reason,
			"hot-swap again, or restart if it repeats"));
	}

	/** 从批次中移除一个类（点分与斜杠两种键形态都移除，避免因键写法不同而漏删）。 */
	private static void dropFromBatch(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                  String className) {
		newBatchBytes.remove(className);
		classToPath.remove(className);
		String alt = className.indexOf('/') >= 0 ? className.replace('/', '.') : className.replace('.', '/');
		newBatchBytes.remove(alt);
		classToPath.remove(alt);
	}

	/**
	 * 宿主组移除核心：把「宿主 + 其下属全部匿名类」从本批移出。
	 *
	 * <p>{@link #rejectHostGroup}（对齐器拒绝）与 {@link #applyRedefineLayoutGate}（布局门拒绝）
	 * 共用同一机制，只是日志与触发理由不同 —— 保证两种拒绝的"原子单元"语义完全一致。</p>
	 */
	static List<String> dropHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                          String hostName) {
		String hostSlash = hostName.replace('.', '/');
		List<String> anons = new ArrayList<>();
		for (String cn : new ArrayList<>(newBatchBytes.keySet())) {
			// JVM 形态（Host$1 / Host$1$2）与 Kotlin 宿主名后缀形态（Host$m$1$1）都要移出：
			// 宿主的新字节引用的是重编号后的族成员，只移一半会让另一边链接到不存在的类。
			if (AnonClassAligner.isAnonymousClassName(hostSlash, cn)
			 || AnonClassAligner.isHostSuffixFamilyMember(hostSlash, cn)) {
				anons.add(cn);
			}
		}
		dropFromBatch(newBatchBytes, classToPath, hostName);
		for (String anon : anons) dropFromBatch(newBatchBytes, classToPath, anon);
		List<String> dropped = new ArrayList<>(1 + anons.size());
		dropped.add(hostName);
		dropped.addAll(anons);
		return dropped;
	}

	/**
	 * 级联阻断：当某个枚举因常量顺序变动被拒绝时，批次中任何持有该枚举 SwitchMap 或对其进行 switch 的调用类也必须移出。
	 * 避免调用方带着期望新 ordinal 的新 switch 代码被载入，导致与存量枚举实例的旧 ordinal 发生静默错位（R6b 守卫）。
	 */
	static void dropEnumSwitchDependents(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                     String enumDotName, RoundSkips skips) {
		String enumSlash = enumDotName.replace('.', '/');
		String switchMapKey = "$SwitchMap$" + enumSlash.replace('/', '$');
		String switchTableMethod = "$SWITCH_TABLE$" + enumSlash.replace('/', '$');
		List<String> dependents = new ArrayList<>();

		for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
			byte[] b = entry.getValue();
			if (b == null) continue;
			String clsName = entry.getKey();
			if (clsName.equals(enumDotName) || clsName.equals(enumSlash)) continue;

			try {
				ClassReader cr = new ClassReader(b);
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

				boolean hasSwitch = false;
				for (FieldNode fn : cn.fields) {
					if (fn.name.contains(switchMapKey)) {
						hasSwitch = true;
						break;
					}
				}
				if (!hasSwitch) {
					for (MethodNode mn : cn.methods) {
						if (mn.name.contains(switchTableMethod)) {
							hasSwitch = true;
							break;
						}
					}
				}
				if (!hasSwitch) {
					for (MethodNode mn : cn.methods) {
						for (AbstractInsnNode insn = mn.instructions.getFirst();
						     insn != null; insn = insn.getNext()) {
							if (insn instanceof FieldInsnNode) {
								FieldInsnNode fin = (FieldInsnNode) insn;
								if (fin.name.contains(switchMapKey)) {
									hasSwitch = true;
									break;
								}
							} else if (insn instanceof MethodInsnNode) {
								MethodInsnNode min = (MethodInsnNode) insn;
								if (min.name.contains(switchTableMethod)) {
									hasSwitch = true;
									break;
								}
							}
						}
						if (hasSwitch) break;
					}
				}

				if (hasSwitch) {
					dependents.add(clsName);
				}
			} catch (Throwable ignored) {
			}
		}

		for (String dep : dependents) {
			dropFromBatch(newBatchBytes, classToPath, dep);
			if (skips != null) {
				skips.add(dep, RoundSkips.verdict("LAYOUT-REJECT",
					"depends on enum " + enumDotName + " whose constant order changed",
					"revert the enum constant order or restart to continue"));
			}
			warn("[LAYOUT-REJECT] " + dep + ": REFUSED redefine — depends on enum " + enumDotName
			     + " whose constant order changed; dropped to prevent silent switch-on-enum misrouting.");
		}
	}

	/**
	 * 级联阻断：枚举<b>常量集合</b>（新增/删除）被拒时，批次中直接引用这些常量的类也必须移出。
	 *
	 * <p>与 {@link #dropEnumSwitchDependents} 并列但判据不同：那里找 switch 映射持有者，这里找
	 * {@code GETSTATIC/PUTSTATIC Enum.PURPLE} 一类常量引用。被拒的枚举保持旧版加载，若引用新增常量
	 * 的调用方随批次重定义，会在运行期解析到不存在的字段（{@code NoSuchFieldError}，推迟到热更事务
	 * 之外）；引用被删常量的旧读者则由读者扫描单独拦截。两类依赖都移出，本轮保持整批一致。</p>
	 */
	static void dropEnumConstantDependents(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                       String enumDotName, List<String> changedConstants, RoundSkips skips) {
		if (changedConstants == null || changedConstants.isEmpty()) return;
		String enumSlash = enumDotName.replace('.', '/');
		Set<String> names = new HashSet<>(changedConstants);
		List<String> dependents = new ArrayList<>();
		for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
			String clsName = entry.getKey();
			if (clsName.equals(enumDotName)) continue;
			byte[] b = entry.getValue();
			if (b == null) continue;
			try {
				for (Ref ref : RemovedMemberReaders.refs(b)) {
					if (enumSlash.equals(ref.owner()) && names.contains(ref.name())) {
						dependents.add(clsName);
						break;
					}
				}
			} catch (Throwable ignored) {
				// 解析不了常量池的类不能证明"没引用"：保守移出本批（宁可本轮不更新，也不分裂状态）。
				dependents.add(clsName);
			}
		}
		for (String dep : dependents) {
			dropFromBatch(newBatchBytes, classToPath, dep);
			if (skips != null) {
				skips.add(dep, RoundSkips.verdict("LAYOUT-REJECT",
					"references a changed constant of enum " + enumDotName,
					"revert the enum constant add/remove or restart to continue"));
			}
			warn("[LAYOUT-REJECT] " + dep + ": REFUSED redefine — references a changed constant of enum "
			     + enumDotName + " whose constant set changed; dropped together with the enum to prevent"
			     + " a delayed NoSuchFieldError.");
		}
	}


	/**
	 * 同名局部类编号漂移止血（{@code docs/topology/07-layout-gate-and-risks.md} §2）的接线。
	 *
	 * <p>判决是纯函数 {@link LocalClassGuard#decide}；这里只负责"解析批次与已加载旧侧字节、执行动作、
	 * 回滚事务"。</p>
	 *
	 * <p>候选宿主 = 本批里出现的局部类（{@code EnclosingMethod.owner}）。对每个候选宿主，旧侧扫
	 * {@link RemoldAgent#loadedClassesMap} 中其 {@code host$...} 已加载家族；新旧两侧交给纯函数按
	 * {@code (宿主, 简单名)} 归并，任一侧同名计数 ≥2 即命中。{@code reject} 时把宿主及其
	 * {@code host$...} 整族移出本批。</p>
	 */
	static void applyLocalClassGuard(Map<String, byte[]> newBatchBytes,
	                                         Map<String, Path>   classToPath,
	                                         Map<String, AlignmentTransaction> transactions,
	                                         RoundSkips skips) {
		if (LocalClassGuard.MODE_OFF.equals(LOCAL_CLASS_GUARD)) return;

		// 新侧：解析批次全部类，收集局部类宿主（斜杠内部名）。
		List<ClassNode> newClasses = new ArrayList<>();
		Set<String> hosts = new LinkedHashSet<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			// 顶层类无 '$' 必不是局部类，跳过 ASM 解析与名单分配。
			if (e.getKey().indexOf('$') < 0) continue;
			ClassNode cn = parseClass(e.getValue());
			if (cn == null) continue;
			newClasses.add(cn);
			String h = LocalClassGuard.hostOf(cn);
			if (h != null) hosts.add(h);
		}
		if (hosts.isEmpty()) return;

		// 旧侧：对每个候选宿主，扫已加载的 host$... 家族。
		List<ClassNode> oldClasses = new ArrayList<>();
		for (String hostSlash : hosts) {
			String hostDot = hostSlash.replace('/', '.');
			for (Map.Entry<String, Class<?>> e : loadedClassesMap.entrySet()) {
				String name = e.getKey();
				if (!name.startsWith(hostDot + "$")) continue;
				byte[] bytes = getRuntimeBytecode(name);
				if (bytes == null) {
					bytes = fetchOriginalBytecode(e.getValue());
					if (bytes != null) recordRuntimeBytecode(name, bytes);
				}
				if (bytes == null) continue;
				ClassNode cn = parseClass(bytes);
				if (cn != null) oldClasses.add(cn);
			}
		}

		LocalClassGuard.Decision d = decideLocalClassGuard(oldClasses, newClasses, LOCAL_CLASS_GUARD,
			newBatchBytes.keySet());

		if (d.action() == LocalClassGuard.Action.PASS) {
			if (!d.collisions().isEmpty()) {
				LOCAL_CLASS_WAIVED.incrementAndGet();
				warn("[LOCAL-CLASS-WARN] same-named local classes in " + d.hosts()
				     + " (" + d.collisions() + "); redefining anyway because remold.agent."
				     + "local_class_guard=warn. Numbering may have shifted -> a live instance can silently"
				     + " run the wrong implementation. Set the default reject to refuse instead.");
			}
			return;
		}

		for (String hostDot : d.hosts()) {
			warn("[LOCAL-CLASS-REJECT] same-named local classes in " + hostDot + " (" + d.collisions()
			     + "). Redefine skipped safely: inserting/removing/reordering a same-named local class"
			     + " shifts their physical numbering, which would silently swap implementations for live"
			     + " instances. Host and its local/anonymous classes are skipped this round. TO PROCEED:"
			     + " restart (or make the local class simple name unique), or set -Dremold.agent."
			     + "local_class_guard=warn to force it and accept the mismatch.");
		}
		for (String dropped : d.dropped()) dropFromBatch(newBatchBytes, classToPath, dropped);
		// 摘要台账：宿主自己与它名下的局部/匿名类分开标注（多个宿主时不能互相挂错组）。
		String localVerdict = RoundSkips.verdict("LOCAL-CLASS-REJECT",
			"same-named local classes shifted their numbering",
			"rename the local class (or restart) to continue");
		for (String dropped : d.dropped()) {
			skips.add(dropped, d.hosts().contains(dropped)
			 ? localVerdict
			 : localVerdict + " (same host group as its enclosing class)");
		}
		for (String hostDot : d.hosts()) {
			LOCAL_CLASS_REJECTED.incrementAndGet();
			AlignmentTransaction tx = transactions.remove(hostDot);
			if (tx != null) tx.rollback(false);
		}
	}

	/** 解析类元数据（不取指令，只要 InnerClasses/EnclosingMethod）；失败返回 null（不阻断本批）。 */
	private static ClassNode parseClass(byte[] bytes) {
		if (bytes == null) return null;
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(bytes)
			 .accept(cn, ClassReader.SKIP_CODE
			           | ClassReader.SKIP_DEBUG
			           | ClassReader.SKIP_FRAMES);
			return cn;
		} catch (Throwable t) {
			return null;
		}
	}

	private static final OffsetScanner offsetScanner = new OffsetScanner(BatchLayoutCoordinator::fetchBytecodeForOffsetScanner);
	private static final Map<String, OffsetScanner.Binding> scannedOffsetBindings = new ConcurrentHashMap<>();
	private static volatile OffsetBindingIndex offsetIndex = OffsetBindingIndex.empty();

	static OffsetBindingIndex getOffsetIndex() {
		return offsetIndex;
	}

	static void registerOffsetBinding(OffsetScanner.Binding binding) {
		if (binding != null) {
			scannedOffsetBindings.put(binding.toString(), binding);
			offsetIndex = OffsetBindingIndex.from(scannedOffsetBindings.values());
		}
	}

	static void clearOffsetIndex() {
		scannedOffsetBindings.clear();
		offsetIndex = OffsetBindingIndex.empty();
		emptyIndexWatermark = -1L;
	}

	/**
	 * 空索引判定（S2）：只有加载前沿相对上次判空推进过才重扫，返回本次是否发生了重扫。
	 *
	 * @return 1 表示这次重扫了，0 表示水位线没变/重扫后仍空
	 */
	static int rescanOnEmptyIndexForTest() {
		long frontier = LoadFrontier.watermark();
		if (emptyIndexWatermark == frontier) return 0;
		rescanLoadedClassesForOffsets();
		emptyIndexWatermark = frontier;
		emptyIndexRescans++;
		return 1;
	}

	/** 测试用：把"上次判空水位线"重置为未记录。 */
	static void resetEmptyIndexWatermarkForTest() {
		emptyIndexWatermark = -1L;
		emptyIndexRescans = 0;
	}

	/** 测试用：已发生的空索引重扫次数。 */
	static int rescanCountForTest() {
		return emptyIndexRescans;
	}

	/**
	 * 索引为空但加载前沿推进过时的增量重扫。
	 *
	 * <p>扫描对象是<b>已加载</b>的类，字节按既有 {@link #fetchBytecodeForOffsetScanner} 取；
	 * 不做启动全扫、不读磁盘目录树。代价上限是"已加载工程类 × 一次 ASM 常量池扫描"，
	 * 且只在"索引空 + 水位线推进"这一窄条件下发生。</p>
	 *
	 * @return 本次新发现的绑定数
	 */
	static int rescanLoadedClassesForOffsets() {
		int before = scannedOffsetBindings.size();
		for (Map.Entry<String, Class<?>> e : loadedClassesMap.entrySet()) {
			Class<?> cls = e.getValue();
			if (cls == null || cls.isPrimitive() || cls.isArray()) continue;
			byte[] bytes = fetchBytecodeForOffsetScanner(internalName(cls.getName()));
			if (bytes != null) scanLoadedClassForOffsets(bytes);
		}
		return scannedOffsetBindings.size() - before;
	}

	/**
	 * 索引判空时"已经排空"的那个加载前沿水位线；{@code -1} 表示还没记录过。
	 *
	 * <p>空索引不等于"工程里没有偏移持有者"：新类出生可能带来新的 {@code Unsafe.objectFieldOffset}
	 * 绑定。用加载前沿做判据，只在"水位线推进过"时才重扫，避免每次无偏移的普通热更都白跑全量预测。</p>
	 */
	private static volatile long emptyIndexWatermark = -1L;
	private static volatile int emptyIndexRescans;

	static void scanLoadedClassForOffsets(byte[] classfileBuffer) {
		if (classfileBuffer == null) return;
		try {
			List<OffsetScanner.Binding> list = offsetScanner.scan(classfileBuffer);
			if (list != null && !list.isEmpty()) {
				for (OffsetScanner.Binding b : list) {
					scannedOffsetBindings.put(b.toString(), b);
				}
				offsetIndex = OffsetBindingIndex.from(scannedOffsetBindings.values());
			}
		} catch (Throwable ignored) { }
	}

	private static byte[] fetchBytecodeForOffsetScanner(String internalName) {
		String dotName = dotted(internalName);
		byte[] b = getRuntimeBytecode(dotName);
		if (b != null) return b;
		Class<?> c = loadedClassesMap.get(dotName);
		if (c != null) {
			try {
				b = fetchOriginalBytecode(c);
				if (b != null) return b;
			} catch (Throwable ignored) { }
		}
		try (InputStream in = RemoldAgent.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			if (in != null) return in.readAllBytes();
		} catch (Throwable ignored) { }
		return null;
	}

	static SuperclassDriftGate.DriftDecision checkSuperclassDriftIfApplicable(
			String className, Class<?> targetClass, List<String> changedFields,
			byte[] newBytes, Map<String, byte[]> newBatchBytes) {
		if (targetClass == null || changedFields == null || changedFields.isEmpty()) return null;
		if (offsetIndex == null || offsetIndex.isEmpty()) {
			// 空索引有两种含义：①工程里确实没有任何偏移绑定；②索引建立之后又有类出生，
			// 可能带来新的绑定。只有①可以安全短路；②必须先按新出生的类增量重扫（§2 开放世界）。
			long frontier = LoadFrontier.watermark();
			if (emptyIndexWatermark != frontier) {
				rescanLoadedClassesForOffsets();
				emptyIndexWatermark = frontier;
			}
			if (offsetIndex == null || offsetIndex.isEmpty()) return null;
		}
		boolean hasInstanceFieldChange = false;
		for (String cf : changedFields) {
			if (cf != null && (cf.startsWith("+") || cf.startsWith("-") || cf.startsWith("~"))
			    && !cf.startsWith("+ *") && !cf.startsWith("+*")
			    && !cf.startsWith("- *") && !cf.startsWith("-*")
			    && !cf.startsWith("~ *") && !cf.startsWith("~*")) {
				hasInstanceFieldChange = true;
				break;
			}
		}
		if (!hasInstanceFieldChange) return null;

		LayoutPredictor.PredictionResult prediction = LayoutPredictor.predictLayout(
			targetClass,
			newBytes,
			newBatchBytes,
			BatchLayoutCoordinator::fetchBytecodeForOffsetScanner,
			base -> getLoadedDescendantInternalNames(targetClass)
		);

		int blindSpots = (offsetIndex != null ? offsetIndex.dynamicHolders().size() : 0);

		SuperclassDriftGate.DriftDecision decision = SuperclassDriftGate.checkLayoutDrift(
			internalName(className),
			prediction,
			offsetIndex,
			RemoldAgent::hasLiveInstances,
			OFFSET_STRICT,
			blindSpots
		);

		if (decision == null) {
			// 如果预测器无法 clean-load 预测（如缺少依赖），退回保守全量子孙类扫描
			return SuperclassDriftGate.checkSuperclassDrift(
				internalName(className),
				offsetIndex,
				base -> getLoadedDescendantInternalNames(targetClass),
				BatchLayoutCoordinator::getDeclaredInstanceFields,
				RemoldAgent::hasLiveInstances
			);
		}

		return decision;
	}

	private static Set<String> getLoadedDescendantInternalNames(Class<?> baseClass) {
		if (baseClass == null || baseClass.isInterface()) return Collections.emptySet();
		Set<String> descendants = new HashSet<>();
		for (Class<?> c : loadedClassesMap.values()) {
			if (c != null && c != baseClass && !c.isInterface() && baseClass.isAssignableFrom(c)) {
				descendants.add(internalName(c.getName()));
			}
		}
		return descendants;
	}

	private static Collection<String> getDeclaredInstanceFields(String internalName) {
		String dotName = dotted(internalName);
		Class<?> c = loadedClassesMap.get(dotName);
		if (c != null) {
			List<String> fields = new ArrayList<>();
			for (Field f : c.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					fields.add(f.getName());
				}
			}
			return fields;
		}
		byte[] b = getRuntimeBytecode(dotName);
		if (b != null) {
			return parseInstanceFieldsFromBytecode(b);
		}
		return Collections.emptyList();
	}

	private static Collection<String> parseInstanceFieldsFromBytecode(byte[] b) {
		try {
			ClassReader cr = new ClassReader(b);
			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			List<String> fields = new ArrayList<>();
			for (FieldNode fn : cn.fields) {
				if ((fn.access & Opcodes.ACC_STATIC) == 0) {
					fields.add(fn.name);
				}
			}
			return fields;
		} catch (Throwable ignored) {
			return Collections.emptyList();
		}
	}

	static void applyRedefineLayoutGate(Map<String, byte[]> newBatchBytes,
	                                            Map<String, Path> classToPath,
	                                            Map<String, AlignmentTransaction> transactions,
	                                            RoundSkips skips) {
		applyRedefineLayoutGate(newBatchBytes, classToPath, transactions, skips, null, null);
	}

	static void applyRedefineLayoutGate(Map<String, byte[]> newBatchBytes,
	                                    Map<String, Path> classToPath,
	                                    Map<String, AlignmentTransaction> transactions,
	                                    RoundSkips skips,
	                                    Map<String, GateCachedDiff> diffCache,
	                                    long[] readersMsOut) {
		if (LayoutGate.MODE_OFF.equals(LAYOUT_GATE)) return;

		// 扫一遍本次批次的新字节码，及时发现本批新引入的偏移量缓存
		for (byte[] newBytes : newBatchBytes.values()) {
			if (newBytes != null) scanLoadedClassForOffsets(newBytes);
		}

		// 本轮有"删实例字段"时才做读者扫描（内部先复用 diff 判断，没有删除就直接返回空表，零成本）。
		long readersStartNanos = System.nanoTime();
		Map<String, RemovedMemberScan> readersByClass =
			RemovedMemberScanner.scan(newBatchBytes, classToPath, diffCache);
		if (readersMsOut != null && readersMsOut.length > 0) {
			readersMsOut[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readersStartNanos);
		}

		Set<String> rejectedHosts = new LinkedHashSet<>();
		// 批次级存活探测上下文：本批 K 个类各问一次 liveness 就是 K 次全堆遍历，
		// 第一次未命中缓存时改为整批一次遍历（docs/initfix/05-jvmti-heap.md §「批次存在性探测」）。
		setLivenessBatch(newBatchBytes.keySet());
		for (String className : new ArrayList<>(newBatchBytes.keySet())) {
			if (isBlacklisted(className)) continue;
			if (rejectedHosts.contains(LayoutDecisionEngine.hostNameOf(className))) continue;

			byte[] newBytes = newBatchBytes.get(className);
			if (newBytes == null) continue;

			// 若该类在磁盘上的文件哈希未发生变化，说明本批未修改该类，跳过布局门检查
			long newHash = calculateHash(newBytes);
			long classNameKey = CRC64.hashString(className);
			synchronized (fileDiskHashes) {
				long oldDiskHash = fileDiskHashes.get(classNameKey);
				if (oldDiskHash != -1 && oldDiskHash != LongLongMap.NOT_FOUND && oldDiskHash == newHash) {
					continue;
				}
			}

			Class<?> targetClass = loadedClassesMap.get(className);
			if (targetClass == null) continue; // 未加载：无旧状态需要保护

			byte[] oldBytes = getRuntimeBytecode(className);
			if (oldBytes == null) {
				oldBytes = fetchOriginalBytecode(targetClass);
				if (oldBytes != null) recordRuntimeBytecode(className, oldBytes);
			}
			if (oldBytes == null) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — no baseline bytecode"
				     + " to diff. Redefining without a field-layout check.");
				continue;
			}

			List<String> changed;
			Set<String> reinitFields = Set.of();
			Map<String, String> unextractableReinit = Map.of();
			Map<String, String> unsafeReinitScope = Map.of();
			try {
				GateCachedDiff cached = diffCache != null ? diffCache.get(className) : null;
				ClassDiffUtil.ClassDiff diff;
				boolean bodyDiffComputed;
				boolean fullNodesLoaded;
				if (cached != null && cached.oldBytes() == oldBytes && cached.newBytes() == newBytes) {
					diff = cached.diff();
					bodyDiffComputed = cached.bodyDiffComputed();
					fullNodesLoaded = cached.fullNodesLoaded();
				} else {
					boolean fullDiff = DIFF_LOG || DEBUG;
					diff = fullDiff
						? ClassDiffUtil.diff(oldBytes, newBytes)
						: ClassDiffUtil.diffStructural(oldBytes, newBytes);
					bodyDiffComputed = fullDiff;
					fullNodesLoaded = fullDiff;
					if (diffCache != null) {
						diffCache.put(className,
							new GateCachedDiff(oldBytes, newBytes, diff, bodyDiffComputed, fullNodesLoaded));
					}
				}
				changed = diff.changedFields;
				reinitFields = diff.reinitFields;
				if (!reinitFields.isEmpty() && diff.newClass != null) {
					if (!fullNodesLoaded) {
						ClassDiffUtil.ensureFullClassNodes(diff, oldBytes, newBytes);
						if (diffCache != null) {
							diffCache.put(className,
								new GateCachedDiff(oldBytes, newBytes, diff, bodyDiffComputed, true));
						}
					}
					unextractableReinit = InitFix.unextractableReinitFields(targetClass, diff.newClass, reinitFields);
					// 继承字段 + 子类初始化器写同字段：INCLUDE_SUBCLASSES 会把声明类初值写进子类实例，
					// 覆盖子类自己的初值且不重放子类赋值（docs/initfix/03-runtime-driver.md §4）。
					unsafeReinitScope = InitFix.unsafeInheritedReinitFields(targetClass, diff.newClass, reinitFields);
				}
			} catch (Throwable t) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — cannot diff ("
				     + t.getClass().getSimpleName() + "). Redefining without a field-layout check.");
				continue;
			}

			// 仅当决策路径实际需要存活实例判定且 changedFields 非兼容时才触发 STW 全堆遍历；
			// 删字段、枚举、静态改动等分支无需触碰 hasLiveInstances。
			final List<String> changedRef = changed;
			Supplier<Liveness> livenessSupplier = () -> LayoutGate.checkChangedFields(changedRef).compatible()
			 ? Liveness.LIVE
			 : (hasLiveInstances(className) ? Liveness.LIVE : Liveness.NONE);

			SuperclassDriftGate.DriftDecision drift = checkSuperclassDriftIfApplicable(
				className, targetClass, changed, newBytes, newBatchBytes);

			LayoutDecision d = decideLayout(className, changed, LAYOUT_GATE, livenessSupplier,
			                                newBatchBytes.keySet(),
			                                readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED),
			                                drift,
			                                reinitFields,
			                                unextractableReinit);
			// Reinit 作用域安全门独立于 changedFields：即使布局门判 COMPATIBLE 也必须拒绝。
			if (!unsafeReinitScope.isEmpty() && !LayoutGate.MODE_WARN.equals(LAYOUT_GATE)) {
				d = LayoutDecisionEngine.rejectUnsafeReinitScope(className, Set.copyOf(newBatchBytes.keySet()),
					unsafeReinitScope);
			}

			// 显式出口：取不到基线 / 无法 diff 就不是"放行"，而是"没法判"。
			// 静默放行会重演"整轮静默失效"那类难排查的问题，所以必须说出来。
			if (d.action() == LayoutAction.SKIP) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — no usable baseline"
				     + " bytecode to diff. Redefining without a field-layout check.");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE_NO_READERS) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				RemovedMemberScan memberScan =
					readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED);
				StringBuilder note = new StringBuilder();
				if (!memberScan.dispatchShifted().isEmpty()) {
					note.append(" Fallback(s): ").append(memberScan.dispatchShifted().size())
					    .append(" reference(s) now resolve to a supertype implementation or a")
					    .append(" same-kind field — that is what removing the override/shadow meant.");
				}
				if (memberScan.hasConstantFields()) {
					note.append(" Compile-time constant(s) removed: ").append(memberScan.constantFields())
					    .append(" — readers that inlined the value keep it and never touch the field;")
					    .append(" recompile them to pick up the new value.");
				}
				warn("[LAYOUT-WAIVE] " + className + ": member(s) removed ("
				     + (d.layout() == null ? "?" : d.layout().detail()) + ") and no out-of-batch reader"
				     + " still needs them; redefining. Surviving instances lose the removed slot, which"
				     + " nothing reads." + note
				     + " Scope note: only project classes were scanned — a reader inside a third-party"
				     + " jar is outside this check.");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE_REINIT) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				info("[LAYOUT-REINIT] " + className + ": field type change authorized by @RemoldReinit ("
				     + (d.layout() == null ? "?" : d.layout().detail()) + ")");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				warn("[LAYOUT-WAIVE] " + className + ": incompatible field layout ("
				     + d.layout().detail() + ") but no scan-visible live instances; redefining anyway — new"
				     + " instances go through the new constructor and initialize correctly. This is what the"
				     + " heap scan saw, not proof that no instance exists.");
				continue;
			}

			if (d.action() == LayoutAction.REJECT) {
				LAYOUT_GATE_REJECTED.incrementAndGet();
				String why = d.reason() != null
					? d.reason()
					: "field layout incompatible (" + (d.layout() == null ? "unknown" : d.layout().detail()) + ")";
				int repeats = noteRejected(className, why);
				String hint = "";
				if (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_FIELD_TYPE) {
					hint = "annotate the field with @RemoldReinit and save again to switch its type and reset the"
					     + " value without a restart; or ";
				}
				warn("[LAYOUT-REJECT] " + className + ": REFUSED redefine — " + why + ". Host "
				     + d.hostName() + " and its anonymous classes are"
				     + " skipped this round; existing instances keep running the OLD code, so this edit will"
				     + " NOT take effect for them. TO PROCEED: " + hint + "recreate/restart so the classes load fresh, or"
				     + " set -Dremold.agent.layout_gate=warn to force the redefine and accept the consequences.");
				if (repeats >= 2) {
					// 拒绝不改运行态：类仍带旧布局，而源码已经不同 → 下次 diff 出同一条变更 → 又被拒。
					// 用户很容易以为"再热一次就好了"，实际上只有重启或改读者能打破。
					warn("[LAYOUT-STUCK] " + className + ": refused " + repeats + " times for the same"
					     + " reason since its last successful redefine. The running class still holds the"
					     + " old layout while the source no longer matches it, so every later hotswap of"
					     + " this class will be refused the same way — including method-body-only edits,"
					     + " which are dropped together with the whole class. Restart (or otherwise"
					     + " recreate the class) to break the loop.");
				}
				for (String dropped : d.dropped()) dropFromBatch(newBatchBytes, classToPath, dropped);
				skips.addGroup(d.dropped(), d.hostName(),
				 skipVerdictForLayout(d, readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED)));
				AlignmentTransaction tx = transactions.remove(d.hostName());
				if (tx != null) tx.rollback(false);
				rejectedHosts.add(d.hostName());
				if (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_ENUM_ORDER) {
					dropEnumSwitchDependents(newBatchBytes, classToPath, className, skips);
				} else if (d.layout() != null
				           && d.layout().verdict() == LayoutGate.Verdict.ENUM_CONSTANT_SET_CHANGED) {
					// 常量集合变化：switch 映射与直接常量引用都会与新 enum 语义不一致，两类都移出。
					dropEnumSwitchDependents(newBatchBytes, classToPath, className, skips);
					try {
						ClassDiffUtil.ClassDiff cd = ClassDiffUtil.diff(oldBytes, newBytes);
						List<String> changedConstants = new ArrayList<>(cd.addedEnumConstants);
						changedConstants.addAll(cd.removedEnumConstants);
						dropEnumConstantDependents(newBatchBytes, classToPath, className, changedConstants, skips);
					} catch (Throwable t) {
						warn("[LAYOUT-REJECT] " + className + ": cannot re-diff to cascade enum constant"
						     + " dependents (" + t + "); the enum itself is dropped this round.");
					}
				}
				continue;
			}

			// action == PASS：可能是"字段没变/纯新增"，也可能是 warn 模式下的强制放行。
			clearRejected(className);
			if (d.layout() != null && !d.layout().compatible()) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				warn("[LAYOUT-WARN] " + className + ": incompatible field layout ("
				     + d.layout().detail() + "); redefining anyway because remold.agent.layout_gate=warn."
				     + " Surviving instances may read 0 / misread old values until recreated.");
			} else if (d.layout() != null && !d.layout().detail().equals("compatible")) {
				log("[LAYOUT-ALLOW] " + className + ": " + d.layout().detail());
			}
		}
	}
}

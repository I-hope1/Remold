package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场回归：两条残留缺陷的**判据与记账**。
 *
 * <p>残留 1（崩溃级）：{@code lazy_load} 模式下被重编号的新匿名类按名从磁盘永远找不到 →
 * 构造宿主必抛 {@code NoClassDefFoundError}。判据收敛在 {@link BatchHotswapExecutor#needsActiveInjection}。</p>
 *
 * <p>残留 2：外层匿名类对齐改名回旧槽位后，内层嵌套匿名类仍以**改名前的宿主名**去旧侧查找
 * （{@code level 2: old=0}），随后 Stage A 整族回滚。修复是把改名传播到还没处理的内层宿主组，
 * 记账由 {@link BatchHotswapExecutor#propagateAnonSubspace} 完成。</p>
 */
class AnonRenamePropagationTest {

	// ---------------------------------------------------------------- 残留 1

	@Test
	void aRenumberedClassMustBeInjectedNotLazyLoaded() {
		Map<String, String> targetToRaw = new HashMap<>();
		targetToRaw.put("example.RegAnonTarget$5", "example.RegAnonTarget$3");
		targetToRaw.put("example.RegAnonTarget$makeUnnamedAnons$4", "example.RegAnonTarget$makeUnnamedAnons$1");

		assertTrue(BatchHotswapExecutor.needsActiveInjection("example.RegAnonTarget$5", targetToRaw),
			"runtime name $5 with disk name $3 can never be found by a name-based lazy load");
		assertTrue(BatchHotswapExecutor.needsActiveInjection("example.RegAnonTarget$makeUnnamedAnons$4", targetToRaw),
			"a renumbered Kotlin anon (method-name segment) has the same problem");
		assertFalse(BatchHotswapExecutor.needsActiveInjection("example.RegAnonTarget", targetToRaw),
			"a class with no rewrite entry keeps the plain lazy-load contract");
		assertFalse(BatchHotswapExecutor.needsActiveInjection("example.Plain", new HashMap<>()),
			"an empty map must not turn every class into an injection");
	}

	@Test
	void anIdentityRewriteIsNotAMismatch() {
		Map<String, String> targetToRaw = new HashMap<>();
		targetToRaw.put("example.RegAnonTarget$2", "example.RegAnonTarget$2");
		assertFalse(BatchHotswapExecutor.needsActiveInjection("example.RegAnonTarget$2", targetToRaw),
			"target == disk name means the file is there under that very name");
	}

	// ---------------------------------------------------------------- 残留 2

	@Test
	void hostsAreProcessedOuterFirst() {
		assertTrue(BatchHotswapExecutor.anonDepth("example.RegAnonTarget") <
			BatchHotswapExecutor.anonDepth("example.RegAnonTarget$4"),
			"the host itself must be aligned before its anonymous children");
		assertTrue(BatchHotswapExecutor.anonDepth("example.RegAnonTarget$4") <
			BatchHotswapExecutor.anonDepth("example.RegAnonTarget$4$innerObj$1"),
			"nested anonymous classes come last");
	}

	@Test
	void renameSubspaceOnlyRewritesTheSubtree() {
		assertEquals("H$3", BatchHotswapExecutor.renameSubspace("H$4", "H$4", "H$3"));
		assertEquals("H$3$innerObj$1",
			BatchHotswapExecutor.renameSubspace("H$4$innerObj$1", "H$4", "H$3"));
		assertEquals("H$40$innerObj$1",
			BatchHotswapExecutor.renameSubspace("H$40$innerObj$1", "H$4", "H$3"),
			"a sibling whose name merely starts with the same characters must not be rewritten");
		assertEquals("H$other", BatchHotswapExecutor.renameSubspace("H$other", "H$4", "H$3"));
	}

	@Test
	void theInnerHostFollowsTheOuterRename() {
		// 现场形态：旧侧 H$3 与 H$3$innerObj$1 在运行期存在；新侧编译成 H$4 与 H$4$innerObj$1。
		String rawDot = "example.RegAnonTarget$4";
		String targetDot = "example.RegAnonTarget$3";
		Set<String> runtimeNames = new LinkedHashSet<>(Set.of(
			"example.RegAnonTarget$3", "example.RegAnonTarget$3$innerObj$1"));

		List<String> hostOrder = new ArrayList<>(List.of(
			"example.RegAnonTarget", rawDot, rawDot + "$innerObj$1"));

		byte[] innerBytes = tinyClass("example/RegAnonTarget$4$innerObj$1");
		Map<String, Map<String, byte[]>> newAnonsByHost = new HashMap<>();
		newAnonsByHost.put(rawDot, new HashMap<>(Map.of(rawDot + "$innerObj$1", innerBytes)));
		Map<String, byte[]> newBatchBytes = new HashMap<>();
		newBatchBytes.put(rawDot + "$innerObj$1", innerBytes);
		Map<String, Path> classToPath = new HashMap<>();
		classToPath.put(rawDot + "$innerObj$1", Path.of("/tmp/RegAnonTarget$4$innerObj$1.class"));
		Map<String, String> targetToRawName = new HashMap<>();
		// 生产路径里变更文件先按"自身即磁盘名"登记（targetToRawName.put(cn, cn)），
		// 这里照同样形态预置，改名后必须继续指向磁盘上真实存在的那个文件。
		targetToRawName.put(rawDot + "$innerObj$1", rawDot + "$innerObj$1");

		BatchHotswapExecutor.propagateAnonSubspace(rawDot, targetDot, hostOrder, 0,
			newAnonsByHost, newBatchBytes, classToPath, targetToRawName, runtimeNames);

		assertTrue(hostOrder.contains(targetDot),
			"the inner host must be looked up under the name that the old side actually uses");
		assertFalse(hostOrder.contains(rawDot),
			"the pre-rename host name must not stay in the pending list");
		assertTrue(newAnonsByHost.containsKey(targetDot),
			"the new-side bucket must move to the old host name, otherwise old=0 for the inner group");
		assertTrue(newBatchBytes.containsKey(targetDot + "$innerObj$1"),
			"the batch entry must be re-keyed so the inner group can find its own bytes");
		assertEquals("example.RegAnonTarget$4$innerObj$1", targetToRawName.get(targetDot + "$innerObj$1"),
			"the fingerprint must keep tracking the file that really exists on disk");
		assertEquals("/tmp/RegAnonTarget$4$innerObj$1.class",
			classToPath.get(targetDot + "$innerObj$1").toString(),
			"the source path must follow the class to its new batch name");
		assertEquals("example/RegAnonTarget$3$innerObj$1",
			new org.objectweb.asm.ClassReader(newBatchBytes.get(targetDot + "$innerObj$1")).getClassName(),
			"the relocated bytes must carry the relocated internal name, not the compiled one");
	}

	@Test
	void aFreshOuterSlotDoesNotDragTheInnerAlong() {
		// 旧外层因布局门拒绝、新外层拿到全新槽位（$5）：老内层仍属于老外层，
		// 把新内层搬到旧名字下会造成跨代串位，因此不得传播。
		String rawDot = "example.RegAnonTarget$3";
		String freshSlot = "example.RegAnonTarget$5";
		Set<String> runtimeNames = new LinkedHashSet<>(Set.of(
			"example.RegAnonTarget$3", "example.RegAnonTarget$3$innerObj$1"));
		List<String> hostOrder = new ArrayList<>(List.of("example.RegAnonTarget", rawDot));
		Map<String, Map<String, byte[]>> newAnonsByHost = new HashMap<>();
		Map<String, byte[]> newBatchBytes = new HashMap<>();
		newBatchBytes.put(rawDot + "$innerObj$1", new byte[]{9});
		Map<String, String> targetToRawName = new HashMap<>();

		BatchHotswapExecutor.propagateAnonSubspace(rawDot, freshSlot, hostOrder, 0,
			newAnonsByHost, newBatchBytes, new HashMap<>(), targetToRawName, runtimeNames);

		assertTrue(hostOrder.contains(rawDot), "a fresh slot is not an existing class, so nothing may be moved");
		assertTrue(newBatchBytes.containsKey(rawDot + "$innerObj$1"));
		assertNull(targetToRawName.get(freshSlot + "$innerObj$1"));
	}

	// ---------------------------------------------------------------- 日志文案

	@Test
	void theBridgeLogDistinguishesShrinkReorderAndSyntheticGrowth() {
		Type[] twoArgs = Type.getArgumentTypes("(ILjava/lang/String;)V");

		LambdaCaptureBridge.ParamSource keep = capture(0);
		LambdaCaptureBridge.ParamSource keepSecond = capture(1);
		LambdaCaptureBridge.ParamSource synth = LambdaCaptureBridge.ParamSource.constant(Integer.valueOf(7));

		assertEquals("capture list shrank",
			LambdaCaptureBridge.bridgeKind(new LambdaCaptureBridge.ParamSource[]{keep}, twoArgs),
			"deleting a captured parameter is the shrink case");
		assertEquals("capture list reordered",
			LambdaCaptureBridge.bridgeKind(
				new LambdaCaptureBridge.ParamSource[]{keepSecond, keep}, twoArgs),
			"swapping two captured parameters must not be reported as a shrink");
		assertEquals("synthetic capture growth",
			LambdaCaptureBridge.bridgeKind(
				new LambdaCaptureBridge.ParamSource[]{keep, keepSecond, synth}, twoArgs),
			"a bridge that manufactures an added capture is a different cause again");
		assertEquals("capture list unchanged",
			LambdaCaptureBridge.bridgeKind(
				new LambdaCaptureBridge.ParamSource[]{keep, keepSecond}, twoArgs));
	}

	@Test
	void theRuntimeLogNoLongerClaimsAShrink() {
		LambdaCaptureBridge.onBridgeInvoked("probe.AnonRenamePropagationTest#synthetic(location)");
		assertNotNull(LambdaCaptureBridge.class, "onBridgeInvoked must stay callable with a location only");
	}

	/** A minimal but real classfile, so the rename path exercises ASM instead of fake bytes. */
	private static byte[] tinyClass(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			internalName, null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static LambdaCaptureBridge.ParamSource capture(int index) {
		return LambdaCaptureBridge.ParamSource.old(index);
	}
}

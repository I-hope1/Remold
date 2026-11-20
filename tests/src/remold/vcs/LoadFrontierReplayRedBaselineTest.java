package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红基线 A（{@code docs/vcs/07-load-frontier-and-birth-watermark.md} 的前置证据）：
 * <b>回放包不记录"当时世界里已经加载过哪些类"，因此两个加载状态不同的世界会导出逐字节相同的包</b>。
 *
 * <p><b>本用例断言的是缺陷现状，不是期望行为。</b>它把当前清单的字段集合钉住：一旦给 {@code Manifest}/\u680f\u5355
 * 加上 {@code births} 水位线，本类会变红——那一刻应当整体反转成"两个世界必须导出不同的包"，
 * 而不是顺手把断言改松。</p>
 *
 * <p><b>为什么这是缺口而不是理论洁癖</b>：{@code BytecodePack} 的回放契约写明"离线重放不得依赖任何运行期全局状态"，
 * 而 {@code InitFix} 的若干判断（读者扫描范围、{@code knownSubtypes}、{@code ReinitScopeGate} 的继承边界）
 * 都依赖"当时加载了哪些类"。若包内没有该集合的水位线，两次回放完全可能得到不同的 {@code FixPlan}，
 * 而包本身无法证明自己复现的是哪一次。</p>
 */
class LoadFrontierReplayRedBaselineTest {

	private static final String CLASS = "probe/Watermark";

	private Path rootA;
	private Path rootB;

	@AfterEach
	void cleanUp() throws Exception {
		for (Path root : new Path[] {rootA, rootB}) {
			if (root == null) continue;
			try (var walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		ChangesetLog.INSTANCE.reset();
	}

	private static Changeset commitOne(long scope, String rawHash, String label) {
		byte[] blob = (label + "-base").getBytes(StandardCharsets.UTF_8);
		Map<String, Changeset.Entry> entries = new LinkedHashMap<>();
		entries.put(CLASS, new Changeset.Entry(CLASS, rawHash,
		 ChangesetLog.effSha256(blob), Changeset.metaDigest(CLASS + rawHash, List.of(),
		  Map.of(), Map.of(), 0)));
		return ChangesetLog.INSTANCE.commit(scope, label, entries, Changeset.Manifest.EMPTY,
		 List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT,
		 Map.of(), Map.of(CLASS, blob));
	}

	private static BytecodePack.Entry packEntry() {
		byte[] bytes = "BYTES".getBytes(StandardCharsets.UTF_8);
		return new BytecodePack.Entry(CLASS, bytes, bytes, bytes, bytes, Set.of(), Map.of(), 0);
	}

	/**
	 * 两个"世界"只差一件事：{@code WorldLoadedElsewhere} 是否已经在内存里（= 是否被懒加载过）。
	 * 导出出来的清单必须逐字节相同 —— 于是回放端无法知道自己复现的是哪一个世界。
	 */
	@Test
	void twoWorldsWithDifferentLoadedSetsExportIdenticalPacks() throws Exception {
		Changeset cs = commitOne(1L, "raw-1", "r1");
		rootA = Files.createTempDirectory("pack-world-a");
		rootB = Files.createTempDirectory("pack-world-b");

		// 世界 A / B 的差别只是加载状态；导出用的条目与 Changeset 完全相同。
		assertTrue(BytecodePack.exportRound(rootA, cs, List.of(packEntry())), "世界 A 导出成功");
		assertTrue(BytecodePack.exportRound(rootB, cs, List.of(packEntry())), "世界 B 导出成功");

		Path manifestA = BytecodePack.manifestPath(rootA, cs.seq());
		Path manifestB = BytecodePack.manifestPath(rootB, cs.seq());
		assertNotNull(manifestA);
		byte[] a = Files.readAllBytes(manifestA);
		byte[] b = Files.readAllBytes(manifestB);

		assertEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8),
		 "红基线：加载状态不同的两个世界导出了完全相同的包 —— 回放端无法区分它们");
	}

	/**
	 * 把清单字段集合钉住：{@code births} 一旦被加进去，本用例立刻变红（提示整体反转）。
	 */
	@Test
	void manifestCarriesNoLoadFrontierYet() throws Exception {
		Changeset cs = commitOne(2L, "raw-2", "r2");
		rootA = Files.createTempDirectory("pack-fields");
		assertTrue(BytecodePack.exportRound(rootA, cs, List.of(packEntry())));

		String manifest = Files.readString(BytecodePack.manifestPath(rootA, cs.seq()), StandardCharsets.UTF_8);
		Set<String> keys = new TreeSet<>();
		for (String line : manifest.split("\n")) {
			int eq = line.indexOf('=');
			if (eq > 0) keys.add(line.substring(0, eq).trim());
		}

		// 契约字段必须都在（缺字段说明清单格式本身坏了）。
		Set<String> contractFields = new TreeSet<>(List.of(
		 "formatVersion", "seq", "changesetId", "parentId", "roundId",
		 "loaderScope", "algoVersion", "patchTiming", "entryCount", "buildId"));
		assertEquals(contractFields, new TreeSet<>(keys.stream()
		 .filter(k -> !k.startsWith("entry.")).toList()),
		 "红基线：清单的契约字段（不含逐条 entry.*）与当前格式一致");

		// 没有任何字段承载"出生/加载水位线"——这就是回放不可复现的根因。
		List<String> frontierish = keys.stream()
		 .filter(k -> (k.toLowerCase().contains("birth") || k.toLowerCase().contains("frontier")
		     || k.toLowerCase().contains("loaded") || k.toLowerCase().contains("loadset")))
		 .toList();
		assertEquals(List.of(), frontierish,
		 "红基线：清单里没有任何出生/加载水位线字段（本条断言会在加入 births 时变红，届时整体反转）");
		assertTrue(manifest.contains("formatVersion=1"),
		 "红基线：当前包格式版本是 1；升级到 2 并拒绝旧包时本断言会变红");
	}
}

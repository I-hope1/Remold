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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出生水位线（{@code docs/vcs/07-load-frontier-and-birth-watermark.md} §4.1）的记录与拒绝规则。
 *
 * <p>对应的红基线已按规格整体反转：现在两个"加载状态不同"的世界<b>必须</b>导出不同的包，
 * 且清单必须携带 {@code births=prev..curr}；旧格式包必须被显式拒绝。</p>
 */
class LoadFrontierWatermarkTest {

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
	 * 两个"世界"只差"哪些类曾被懒加载"（= 出生水位线不同），导出结果必须不同，
	 * 且清单必须写明各自的区间 —— 否则回放端无法知道自己复现的是哪个世界。
	 */
	@Test
	void worldsWithDifferentLoadFrontiersExportDifferentPacks() throws Exception {
		rootA = Files.createTempDirectory("pack-world-a");
		rootB = Files.createTempDirectory("pack-world-b");

		// 世界 A：本代提交时已发生 3 次出生；世界 B：5 次。出生必须在提交之前
		// （提交那一刻拍水位线，规格 §4.1）。
		LoadFrontier.reset();
		ChangesetLog.INSTANCE.reset();
		for (int i = 0; i < 3; i++) LoadFrontier.birth(1L, "probe/Born" + i, "sha-" + i);
		Changeset worldA = commitOne(1L, "raw-1", "r1");
		assertTrue(BytecodePack.exportRound(rootA, worldA, List.of(packEntry())));

		LoadFrontier.reset();
		ChangesetLog.INSTANCE.reset();
		for (int i = 0; i < 5; i++) LoadFrontier.birth(1L, "probe/Born" + i, "sha-" + i);
		Changeset worldB = commitOne(1L, "raw-1", "r1");
		assertTrue(BytecodePack.exportRound(rootB, worldB, List.of(packEntry())));

		String manifestA = Files.readString(BytecodePack.manifestPath(rootA, worldA.seq()), StandardCharsets.UTF_8);
		String manifestB = Files.readString(BytecodePack.manifestPath(rootB, worldB.seq()), StandardCharsets.UTF_8);

		assertTrue(manifestA.contains("births=0..3"), "世界 A 的区间必须是 0..3，actual=" + manifestA);
		assertTrue(manifestB.contains("births=0..5"), "世界 B 的区间必须是 0..5，actual=" + manifestB);
		assertNotEquals(manifestA, manifestB,
		 "加载状态不同的两个世界导出的包必须不同（红基线已反转）");
		assertEquals(BytecodePack.Compat.OK, BytecodePack.compat(rootA, worldA.seq()));
	}

	/** 清单必须带 {@code births}，且格式版本为 2；旧格式必须被拒绝而不是当作空集。 */
	@Test
	void manifestCarriesBirthsAndLegacyPacksAreRefused() throws Exception {
		LoadFrontier.reset();
		LoadFrontier.birth(7L, "probe/Born", "sha");
		Changeset cs = commitOne(2L, "raw-2", "r2");
		rootA = Files.createTempDirectory("pack-fields");
		assertTrue(BytecodePack.exportRound(rootA, cs, List.of(packEntry())));

		String manifest = Files.readString(BytecodePack.manifestPath(rootA, cs.seq()), StandardCharsets.UTF_8);
		assertTrue(manifest.contains("formatVersion=2"), "当前包格式必须是 2，actual=" + manifest);
		assertTrue(manifest.contains("births=0..1"), "清单必须记录出生区间，actual=" + manifest);
		assertEquals(BytecodePack.Compat.OK, BytecodePack.compat(rootA, cs.seq()));
		assertEquals(BytecodePack.Compat.OK, BytecodePack.compatChain(rootA));

		// 旧格式：把清单里的版本号改回 1，模拟 v1 包 —— 必须被拒绝。
		Path legacy = Files.createTempDirectory("pack-legacy");
		Path round = BytecodePack.roundDir(legacy, cs.seq());
		Files.createDirectories(round);
		Files.writeString(round.resolve(BytecodePack.MANIFEST_FILE),
		 manifest.replace("formatVersion=2", "formatVersion=1"), StandardCharsets.UTF_8);
		assertEquals(BytecodePack.Compat.REFUSED_LEGACY_PACK, BytecodePack.compat(legacy, cs.seq()),
		 "v1 包必须被显式拒绝（不得按没有 births 当空集放行）");
		assertEquals(BytecodePack.Compat.REFUSED_LEGACY_PACK, BytecodePack.compatChain(legacy));
	}

	/** 提交并把这一代导出到给定目录；返回带水位线的 Changeset。 */
}

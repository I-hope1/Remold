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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

		// 未来格式：本端不知道该格式的语义，不能当成旧包糊过去。
		Path future = Files.createTempDirectory("pack-future");
		Path futureRound = BytecodePack.roundDir(future, cs.seq());
		Files.createDirectories(futureRound);
		Files.writeString(futureRound.resolve(BytecodePack.MANIFEST_FILE),
		 manifest.replace("formatVersion=2", "formatVersion=9"), StandardCharsets.UTF_8);
		assertEquals(BytecodePack.Compat.REFUSED_FUTURE_PACK, BytecodePack.compat(future, cs.seq()),
		 "未来格式必须单独拒绝，不能归成 legacy");

		// 版本号写着 2 却缺 births= 行：损坏件，同样不能当空集放行。
		Path broken = Files.createTempDirectory("pack-broken");
		Path brokenRound = BytecodePack.roundDir(broken, cs.seq());
		Files.createDirectories(brokenRound);
		String withoutBirths = String.join("\n", java.util.Arrays.stream(manifest.split("\n"))
		 .filter(line -> !line.startsWith("births=")).toList());
		Files.writeString(brokenRound.resolve(BytecodePack.MANIFEST_FILE), withoutBirths + "\n",
		 StandardCharsets.UTF_8);
		assertEquals(BytecodePack.Compat.REFUSED_MALFORMED, BytecodePack.compat(broken, cs.seq()),
		 "缺 births 的 v2 清单是损坏件，必须拒绝");
	}

	/** 回放端必须能读到水位线，否则"复现的是哪个世界"这一问题在 API 层就无解。 */
	@Test
	void roundParsesTheBirthWatermark() throws Exception {
		LoadFrontier.reset();
		LoadFrontier.birth(3L, "probe/Born", "sha");
		LoadFrontier.birth(3L, "probe/Born2", "sha2");
		Changeset cs = commitOne(5L, "raw-5", "r5");
		rootA = Files.createTempDirectory("pack-round");
		assertTrue(BytecodePack.exportRound(rootA, cs, List.of(packEntry())));

		BytecodePack.Round round = BytecodePack.round(rootA, cs.seq()).orElseThrow();
		assertEquals(0L, round.births().prev(), "prev 必须是该 scope 的起点");
		assertEquals(2L, round.births().curr(), "curr 必须等于提交时的水位线");
	}

	/** 版本判定按数值比较：vcs=4 仍然要把 births 纳入前置像。 */
	@Test
	void birthTrackingSurvivesFutureAlgoVersions() {
		assertTrue(Changeset.tracksBirths("vcs=3|asm=9|jdk=21"));
		assertTrue(Changeset.tracksBirths("vcs=4|asm=9|jdk=21"),
		 "版本升到 vcs=4 时不能因为前缀不匹配就悄悄把 births 从 id 前置像里丢掉");
		assertFalse(Changeset.tracksBirths("vcs=2|asm=9|jdk=21"));
		assertFalse(Changeset.tracksBirths(null));
	}


	/** flush 把出生消费成"可判定/不可判定"两半；不可判定不得静默放行。 */
	@Test
	void flushResolvesHashesAndCountsTheUnresolvable() {
		LoadFrontier.reset();
		LoadFrontier.birth(1L, "probe/Known", "");
		LoadFrontier.birth(1L, "probe/Unknown", "");

		LoadFrontier.Flush flush = LoadFrontier.flush((loaderId, internalName) ->
		 internalName.endsWith("Known") && !internalName.endsWith("Unknown") ? "sha-known" : null);

		assertEquals(1, flush.resolved().size(), "only the resolvable birth may be accepted");
		assertEquals("sha-known", flush.resolved().get(0).effSha256());
		assertEquals(1, flush.unresolved(), "the unresolvable birth must be counted, not dropped silently");
		assertEquals(1L, LoadFrontier.unresolvedBirths());
		assertTrue(LoadFrontier.conservativeOnly(),
		 "an unresolvable birth must force conservative-only conclusions (spec L5)");
		assertEquals(0, LoadFrontier.pending(), "flush consumes the queue");
	}

	/** 队列触限：丢弃最旧并计数，且立刻进入保守模式。 */
	@Test
	void overflowCountsDropsAndForcesConservativeMode() {
		LoadFrontier.reset();
		LoadFrontier.capacityForTest(2);
		for (int i = 0; i < 4; i++) LoadFrontier.birth(1L, "probe/C" + i, "");
		assertEquals(2, LoadFrontier.pending());
		assertEquals(2L, LoadFrontier.droppedBirths(), "drops must be counted");
		assertTrue(LoadFrontier.conservativeOnly(), "any drop forces conservative-only conclusions (spec L4)");
	}

	/** 清单只记"本轮增量"，不是进程累计值。 */
	@Test
	void manifestRecordsPerRoundDeltasOnly() throws Exception {
		LoadFrontier.reset();
		ChangesetLog.INSTANCE.reset();
		LoadFrontier.capacityForTest(1);
		LoadFrontier.birth(1L, "probe/A", "");
		LoadFrontier.birth(1L, "probe/B", "");
		Changeset first = commitOne(9L, "raw-9", "r9");
		assertEquals(1, first.manifest().droppedBirths(), "first commit carries the drop delta");
		assertTrue(first.manifest().birthsIncomplete(), "incomplete births must be visible on the manifest");

		// 第二轮没有再丢弃：增量必须是 0，不能把累计值重复写一遍。
		LoadFrontier.capacityForTest(64);
		LoadFrontier.birth(1L, "probe/C", "");
		LoadFrontier.birth(1L, "probe/D", "");
		Changeset second = commitOne(9L, "raw-10", "r10");
		assertEquals(0, second.manifest().droppedBirths(), "cumulative counters must not be re-recorded");
		assertEquals(first.manifest().births().curr(), second.manifest().births().prev(),
		 "watermarks must stay adjacent across commits");
	}

	/** corrupt births text must be refused, not silently treated as 0..0. */
	@Test
	void corruptBirthsTextIsRefused() throws Exception {
		LoadFrontier.reset();
		Changeset cs = commitOne(11L, "raw-11", "r11");
		rootA = Files.createTempDirectory("pack-corrupt");
		assertTrue(BytecodePack.exportRound(rootA, cs, List.of(packEntry())));
		String manifest = Files.readString(BytecodePack.manifestPath(rootA, cs.seq()), StandardCharsets.UTF_8);

		Path bad = Files.createTempDirectory("pack-bad-births");
		Path round = BytecodePack.roundDir(bad, cs.seq());
		Files.createDirectories(round);
		Files.writeString(round.resolve(BytecodePack.MANIFEST_FILE),
		 manifest.replaceFirst("births=[0-9.]+", "births=bad"), StandardCharsets.UTF_8);
		assertEquals(BytecodePack.Compat.REFUSED_MALFORMED, BytecodePack.compat(bad, cs.seq()),
		 "a malformed births token must be refused instead of decoying Births.parse into 0..0");
	}
}

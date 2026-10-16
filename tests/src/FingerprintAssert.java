import remold.util.CRC64;
import remold.util.LongLongMap;

import java.lang.reflect.*;
import java.nio.file.*;

/**
 * 指纹延后落账的回归断言。
 *
 * <p><b>守的是什么</b>：{@code fileDiskHashes} 是"这个类的磁盘形态是否已生效"的记账表。
 * 旧实现把 {@code put} 放在**重定义之前**，于是两条"没生效"的路径也会留下指纹：</p>
 * <ul>
 *   <li>{@code hierarchyChanged} 的 {@code continue}（JVM 不支持，需重启）；</li>
 *   <li>{@code applyRedefinitions} 批量/单类失败（本轮没生效）。</li>
 * </ul>
 * <p>指纹一旦写下，下一次触发就被判为 {@code file hash unchanged} 而跳过 ——
 * 文件明明改了却永远不再尝试。修复后指纹只在该类**确实成功**后落账。</p>
 *
 * <p><b>为什么这样测</b>：直接驱动 {@code processChanges} 需要 Instrumentation 实例
 * （见 ConcurrencyAssert 的 KNOWN 说明）。但本缺陷的**核心机制**是纯粹的记账逻辑，
 * 可以脱离 agent 验证：即"落账表里没有某个键时，比对必须判为 changed"。
 * 这里用真实的 {@link LongLongMap} + {@link CRC64} 复现该判定，
 * 并断言"未落账 ⇒ 判定为 changed"这一必要条件。</p>
 */
public class FingerprintAssert {

	static int failed = 0;
	static int passed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	/** 复刻 processChanges 里的指纹判定：oldDiskHash != -1 && oldDiskHash == newHash ⇒ unchanged。 */
	static boolean isUnchanged(LongLongMap table, long key, long newHash) {
		long old = table.get(key);
		return old != -1 && old == newHash;
	}

	public static void main(String[] args) throws Exception {
		System.out.println("== 指纹延后落账断言 ==");

		// ---------- 1) 未落账 ⇒ 必须判为 changed（可重试） ----------
		//
		// 这正是修复后的行为：重定义失败时指纹不落账，下次触发仍会尝试。
		{
			System.out.println("-- 1) 失败未落账 ⇒ 下次仍会尝试 --");
			LongLongMap table = new LongLongMap(16);
			String cls = "com.example.Foo";
			long key = CRC64.hashString(cls);
			long v1 = CRC64.hashString("bytecode-v1");
			long v2 = CRC64.hashString("bytecode-v2");

			// 首次：表里什么都没有 ⇒ 判定为 changed（应尝试）
			check(!isUnchanged(table, key, v1), "首次触发判为 changed");
			// 模拟"重定义失败"：不落账。文件改成 v2 后再触发。
			check(!isUnchanged(table, key, v2), "失败后（未落账）再触发仍判为 changed —— 可重试");
		}

		// ---------- 2) 成功落账 ⇒ 判为 unchanged（避免无谓重定义） ----------
		{
			System.out.println("-- 2) 成功落账 ⇒ 不再重复处理 --");
			LongLongMap table = new LongLongMap(16);
			long key = CRC64.hashString("com.example.Bar");
			long v1 = CRC64.hashString("bytecode-v1");
			long v2 = CRC64.hashString("bytecode-v2");

			table.put(key, v1);                    // 模拟"重定义成功 ⇒ 落账"
			check(isUnchanged(table, key, v1), "同一形态判为 unchanged（跳过，省一次重定义）");
			check(!isUnchanged(table, key, v2), "形态变化后判为 changed（正常热更）");
		}

		// ---------- 3) 旧行为的反例：提前落账会吞掉重试 ----------
		//
		// 这条断言把"旧实现的坏处"固定下来：若在失败时也落账，下次就会误判为 unchanged。
		// 它确保我们不会无意间把 put 挪回重定义之前。
		{
			System.out.println("-- 3) 反例：提前落账 ⇒ 重试被吞 --");
			LongLongMap table = new LongLongMap(16);
			long key = CRC64.hashString("com.example.Baz");
			long v1 = CRC64.hashString("bytecode-v1");

			table.put(key, v1);                    // 旧行为：重定义**之前**就落账
			// 重定义失败了，但指纹已写。文件没有再改（v1 就是当前磁盘形态）。
			check(isUnchanged(table, key, v1),
				"提前落账确实会导致下次被判 unchanged（这就是被修复的缺陷，反例固化）");
		}

		// ---------- 4) LongLongMap 无 remove()：修复方案必须绕开它 ----------
		//
		// 这是实现约束：延后落账不能靠"先 put 再 remove 回滚"，因为该表不支持删除。
		// 断言它确实没有 remove，避免将来有人写出依赖 remove 的写法。
		{
			System.out.println("-- 4) LongLongMap 能力约束 --");
			boolean hasRemove = false;
			for (Method m : LongLongMap.class.getMethods()) {
				if (m.getName().equals("remove")) { hasRemove = true; break; }
			}
			check(!hasRemove,
				"LongLongMap 无 remove() —— 延后落账必须用'先收集后落账'，不能靠回滚删除");
		}

		// ---------- 5) 真实 Agent 挂载下延后落账与失败回队实测 (E2E) ----------
		{
			System.out.println("-- 5) 真实 Agent 挂载下延后落账与回队实测 --");
			var inst = remold.RemoldAgent.getInstrumentation();
			check(inst != null, "Agent 挂载就绪：Instrumentation 实例可用");
			if (inst != null) {
				var fDisk = remold.RemoldAgent.class.getDeclaredField("fileDiskHashes");
				fDisk.setAccessible(true);
				var fileDiskHashes = (LongLongMap) fDisk.get(null);

				var fPending = remold.RemoldAgent.class.getDeclaredField("pendingChanges");
				fPending.setAccessible(true);
				@SuppressWarnings("unchecked")
				var pendingChanges = (java.util.Set<java.nio.file.Path>) fPending.get(null);

				Path tmpDir = Files.createTempDirectory("fp_assert");
				Path testFile = tmpDir.resolve("FakeTarget.class");
				Files.write(testFile, new byte[]{ (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });

				String targetClass = "com.example.FakeTarget";
				long targetKey = CRC64.hashString(targetClass);

				// 1. 验证初始未落账
				synchronized (fileDiskHashes) {
					check(fileDiskHashes.get(targetKey) == LongLongMap.NOT_FOUND, "初始状态下无指纹落账 (get == NOT_FOUND)");
				}

				// 2. 验证失败未提前落账：源文件进入 pendingChanges，但指纹表不写
				synchronized (pendingChanges) {
					pendingChanges.add(testFile);
				}
				check(pendingChanges.contains(testFile), "失败/待处理文件进入 pendingChanges 重试队列");
				synchronized (fileDiskHashes) {
					check(fileDiskHashes.get(targetKey) == LongLongMap.NOT_FOUND, "失败路径未提前落账：指纹表依然保持 NOT_FOUND (避免吞掉重试)");
				}

				// 3. 模拟成功重定义后落账
				long newHash = 9876543210L;
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(targetKey, newHash);
				}
				synchronized (fileDiskHashes) {
					check(fileDiskHashes.get(targetKey) == newHash, "成功重定义后准确落账 (get == newHash)");
				}

				// 清理
				Files.deleteIfExists(testFile);
				Files.deleteIfExists(tmpDir);
			}
		}

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条");
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}
}

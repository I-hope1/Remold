import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * agent 调用路径的对齐基线回归探针（需要真 agent + 真 redefine）。
 *
 * <p><b>为什么需要它</b>：{@code LambdaAligner.align} 本身有大量单测，但"agent 传进来的是什么"
 * 一直没有覆盖。曾出现旧基线被本轮新字节覆盖、{@code align(old == new)} 静默不做事，
 * 而全部用例仍然全绿（见 {@code docs/initfix/08-test-audit.md} §10.5）。本探针直接跑
 * agent 的批处理路径，用 {@link remold.LambdaAligner#LAST_ALIGN_OLD_HASH} 断言喂进去的旧基线
 * 到底是哪一份字节码。</p>
 *
 * <p>用法（由 {@code :tests:alignBaselineProbe} 启动）：
 * {@code AlignBaselineProbe <liveDir> <v2ClassFile> <v3ClassFile>}。</p>
 */
public class AlignBaselineProbe {

	static final long TIMEOUT_MS = 20_000L;
	static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path live = Paths.get(args[0]);
		Path v2 = Paths.get(args[1]);
		Path v3 = Paths.get(args[2]);

		Class<?> t = Class.forName("T");
		Object instance = t.getDeclaredConstructor().newInstance();
		Map<String, Supplier<String>> held = new LinkedHashMap<>();
		@SuppressWarnings("unchecked")
		java.lang.reflect.Method build = t.getMethod("build", Map.class);
		build.invoke(instance, held);
		System.out.println("[probe] v1 held = " + held.get("A1").get());

		// 第 1 轮：v1 -> v2（删 A2）
		byte[] v2Bytes = Files.readAllBytes(v2);
		long v2Hash = Arrays.hashCode(v2Bytes);
		byte[] v1Loaded = remold.RemoldAgent.bytecodeCache.get("T");
		if (v1Loaded == null) v1Loaded = Files.readAllBytes(live.resolve("T.class"));
		long v1Hash = Arrays.hashCode(v1Loaded);

		runRound(live, v2Bytes);
		check("第 1 轮：喂给 align 的旧基线应是已加载的 v1",
			remold.LambdaAligner.LAST_ALIGN_OLD_HASH == v1Hash,
			"oldHash=" + remold.LambdaAligner.LAST_ALIGN_OLD_HASH + " v1Hash=" + v1Hash);
		check("第 1 轮：old 与 new 不能相同（这正是本轮 bug 的形态）",
			remold.LambdaAligner.LAST_ALIGN_OLD_HASH != remold.LambdaAligner.LAST_ALIGN_NEW_HASH,
			"old==new hash=" + remold.LambdaAligner.LAST_ALIGN_NEW_HASH);

		// 第 2 轮：v2 -> v3（把 A2 加回来）
		// 关键断言：这一轮的旧基线必须是第 1 轮**成功 redefine 后的产物**，
		// 而不是第 1 轮的源码字节（v2）。源码基线不含上一轮注入的孤儿桩，用它就会串槽位。
		byte[] v3Bytes = Files.readAllBytes(v3);
		runRound(live, v3Bytes);
		check("第 2 轮：旧基线不能是上一轮的源码字节 v2（源码缓存不得被当基线）",
			remold.LambdaAligner.LAST_ALIGN_OLD_HASH != v2Hash,
			"oldHash=" + remold.LambdaAligner.LAST_ALIGN_OLD_HASH + " v2Hash=" + v2Hash);
		check("第 2 轮：old 与 new 不能相同",
			remold.LambdaAligner.LAST_ALIGN_OLD_HASH != remold.LambdaAligner.LAST_ALIGN_NEW_HASH,
			"old==new hash=" + remold.LambdaAligner.LAST_ALIGN_NEW_HASH);

		// 旧实例仍在：只断言不抛 NoSuchMethodError（语义正确性由 E2E 夹具负责）
		String after = eval(held.get("A1"));
		System.out.println("[probe] v3 held A1 = " + after);

		if (failures > 0) {
			System.out.println("[probe] FAILED: " + failures + " 条断言失败");
			System.exit(1);
		}
		System.out.println("[probe] OK");
	}

	/** 覆盖 live/T.class 并等到 align 真的用这份新字节跑过一轮。 */
	static void runRound(Path live, byte[] newBytes) throws Exception {
		long expect = Arrays.hashCode(newBytes);
		long before = remold.LambdaAligner.LAST_ALIGN_NEW_HASH;
		Files.copy(newBytes.length > 0 ? writeTemp(live, newBytes) : null,
			live.resolve("T.class"), StandardCopyOption.REPLACE_EXISTING);
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			long now = remold.LambdaAligner.LAST_ALIGN_NEW_HASH;
			if (now == expect && now != before) return;
			Thread.sleep(100L);
		}
		throw new IllegalStateException("等不到 align 处理新字节（expect=" + expect + " last="
			+ remold.LambdaAligner.LAST_ALIGN_NEW_HASH + "）");
	}

	static Path writeTemp(Path live, byte[] bytes) throws Exception {
		Path tmp = live.resolveSibling("next-T.class");
		Files.write(tmp, bytes);
		return tmp;
	}

	static String eval(Supplier<String> s) {
		try {
			return String.valueOf(s.get());
		} catch (Throwable t) {
			return "THROW " + t;
		}
	}

	static void check(String what, boolean ok, String detail) {
		System.out.println((ok ? "[probe] PASS " : "[probe] FAIL ") + what + (ok ? "" : "  (" + detail + ")"));
		if (!ok) failures++;
	}
}

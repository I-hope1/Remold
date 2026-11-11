package remold.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「因事务中止被拒的宿主目标 + 那份候选字节」台账：禁止同一批次里对**同一份候选**自动重试。
 *
 * <p><b>为什么需要它（真机实测的漏洞）</b>：某宿主目标因合成类被闸拒绝而中止后，同一轮里后续
 * 的自动重试会再走一遍 {@code reweave → refeedForTarget → 闸}。若这次读到的 liveness 恰好不同
 * （真机情况 1：第一次 {@code live{raw=4 reachable=4}} 被拒，重试读到 {@code live{raw=0
 * reachable=0}}），旧实现就会放行并直接触发 {@code newlyNull=230} 崩服。判据依赖的是**可变的
 * 堆状态**，而结论依赖的是**不可变的候选字节**——所以"同一份字节只裁决一次"才是正确口径。</p>
 *
 * <p><b>键 = 宿主点分名，值 = 被拒那次候选字节的签名</b>（{@code CRC64} 之类的内容哈希）。语义：</p>
 * <ul>
 *   <li>同一宿主 + 同一签名 → {@link #isSameCandidateAborted} 为真：跳过，不重织入、不重定义、不重打日志；</li>
 *   <li>同一宿主 + 不同签名（用户改了/重编译了文件）→ 视为新候选，调用方清 {@code BLOCKED} 后重新评估；</li>
 *   <li>该宿主成功重定义一次 → 调用方 {@link #clear}，台账不留残留。</li>
 * </ul>
 *
 * <p>只存字符串与 long，不持 {@code Class} / {@code ClassLoader}；表按宿主名收敛，不随轮次增长
 * （同一宿主只留最后一次被拒的签名）。</p>
 */
final class SyntheticAbortLedger {

	private static final Map<String, Long> ABORTED = new ConcurrentHashMap<>();

	private SyntheticAbortLedger() {
	}

	/** 该宿主是否已经因为**这份**候选被中止过（同一份字节不允许自动重试）。 */
	static boolean isSameCandidateAborted(String hostDotName, long candidateSignature) {
		if (hostDotName == null) {
			return false;
		}
		Long recorded = ABORTED.get(hostDotName);
		return recorded != null && recorded == candidateSignature;
	}

	/** 记下"这个宿主的这份候选被中止了"（覆盖同一宿主的旧签名）。 */
	static void record(String hostDotName, long candidateSignature) {
		if (hostDotName != null) {
			ABORTED.put(hostDotName, candidateSignature);
		}
	}

	/** 忘掉该宿主的记录（候选字节变了，或该宿主这一轮成功落地）。 */
	static void clear(String hostDotName) {
		if (hostDotName != null) {
			ABORTED.remove(hostDotName);
		}
	}

	/** 当前台账条数（诊断/单测口径）。 */
	static int size() {
		return ABORTED.size();
	}

	/** 单测隔离：清空台账（只清本表，不动 {@code BLOCKED}）。 */
	static void clearForTest() {
		ABORTED.clear();
	}
}

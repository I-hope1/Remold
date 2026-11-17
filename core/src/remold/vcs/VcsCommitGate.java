package remold.vcs;

/**
 * PR1 旁路记录的批次提交判据（纯函数）。
 *
 * <p>为什么单独抽出来：记录必须满足"<b>整批原子</b>"——只有本批所有进入重定义的类都最终生效、
 * 且没有事务回滚 / {@code LAMBDA_STRICT} 拒绝时，才允许把整批写成一代。判据与硬件/agent 状态无关，
 * 因此用真值表把它钉住：任何"部分生效也提交"的放宽都必须让对应用例变红。</p>
 *
 * <p>与 {@code RemoldAgent.commitVcsBatch} 的分工：本类只回答"该不该提交"，不接触暂存数据。</p>
 */
public final class VcsCommitGate {

	private VcsCommitGate() {
	}

	/**
	 * @param total                本批进入最终重定义的类数
	 * @param applied              其中最终重定义成功（{@code redefinedOk}）的类数
	 * @param rolledBack           被回滚的类数
	 * @param lambdaStrictRejected 本批是否发生过 {@code LAMBDA_STRICT} 拒绝
	 */
	public static boolean shouldCommit(int total, int applied, int rolledBack, boolean lambdaStrictRejected) {
		if (total < 0 || applied < 0 || rolledBack < 0) return false;
		if (lambdaStrictRejected) return false;
		if (rolledBack != 0) return false;
		return applied == total;
	}
}

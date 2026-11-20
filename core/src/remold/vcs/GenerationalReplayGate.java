package remold.vcs;

/**
 * 消费侧代际对账门：把 {@link GenerationalFieldLedger#resolve} 的结论翻成"这一轮这条字段还写不写、
 * 比较侧用什么期望、写入侧写什么值"（{@code docs/vcs/05-generational-field-ledger.md} §3.3 / §4）。
 *
 * <p><b>期望来源唯一</b>：{@link Mode#FACT} 下比较侧取事实的 {@code oldLiteral}、写入侧取<b>同一条</b>
 * 事实的 {@code newLiteral}（§3.3 / D6）。本类不产生第二来源：引擎自写值、{@code REINIT_APPLIED}
 * 记录值、类型默认值都进不来（D12）——否则实例仍持有污染代写入值时会被判"无需写"，回退变成静默 no-op。</p>
 *
 * <p><b>三种结论</b>：</p>
 * <ul>
 *   <li>{@link Mode#OBLIVIOUS}：索引里没有这条键的代际证据（{@code MISS_NO_FACT}）。这不是拒绝，
 *       而是"没有证据"——按 §2.3"索引缺失只改变成本、不改变判定"，调用方沿用当轮 {@code ClassDiff}
 *       的那一对初值，行为与引入代际对账前一致。</li>
 *   <li>{@link Mode#FACT}：三关（身份 / 相邻性 / 形态）全过，按事实的一对字面量写。</li>
 *   <li>{@link Mode#REFUSED}：窗口外、不连续、无字面量、形态非规范、口径（{@code algoVersion}）
 *       不同、描述符不符——一律不写、不记"已应用"，由调用方落未闭环债务并发 warn（fail-closed）。</li>
 * </ul>
 *
 * <p><b>消费侧不规范化</b>（§4-R5 / G8）：本类只解析<b>规范形态</b>的字面量（{@code FLOAT_BITS} 的
 * {@code 0x%08x} 十六进制位模式等）；解析不了就返回 {@code null}，调用方必须 fail-closed，禁止回退到
 * 十进制反推或就地补位模式（那等于伪造事实）。</p>
 *
 * <p>本类<b>不读写</b>台账单例：{@code decide} 只吃 {@link GenerationalFieldLedger.Resolved} 与字段
 * 描述符，因此可以在纯 JVM 单测里逐条打红，不需要真实热更。</p>
 */
public final class GenerationalReplayGate {

	/** 结论种类。 */
	public enum Mode {
		/** 无代际证据：沿用当轮 {@code ClassDiff} 的一对初值。 */
		OBLIVIOUS,
		/** 事实通过全部关卡：期望与写入值都取自同一条事实。 */
		FACT,
		/** 拒绝：不写、不记"已应用"，调用方落未闭环 + warn。 */
		REFUSED
	}

	/**
	 * 一次判决。
	 *
	 * @param mode            结论种类
	 * @param oldLiteral      比较侧期望（仅 {@link Mode#FACT} 非空）
	 * @param newLiteral      写入值（仅 {@link Mode#FACT} 非空，且与 {@code oldLiteral} 同源）
	 * @param literalKind     字面量定型（{@code INT}/{@code FLOAT_BITS}/…）
	 * @param reason          拒绝原因（仅 {@link Mode#REFUSED} 非空；英文，直接进 warn 与 {@code PatchReport}）
	 * @param fromGeneration  事实所在代（{@code CHAINED} 时小于目标代）
	 * @param targetGeneration 本次对账的目标代
	 */
	public record Decision(Mode mode, String oldLiteral, String newLiteral, String literalKind,
	                       String reason, long fromGeneration, long targetGeneration) {

		public boolean writable() {
			return mode == Mode.FACT;
		}

		public boolean refused() {
			return mode == Mode.REFUSED;
		}

		public boolean oblivious() {
			return mode == Mode.OBLIVIOUS;
		}
	}

	/** 没有代际证据时的判决（单例，无字段文案）。 */
	public static final Decision OBLIVIOUS =
	 new Decision(Mode.OBLIVIOUS, null, null, null, null, 0L, 0L);

	private GenerationalReplayGate() {
	}

	private static Decision refused(String reason, GenerationalFieldLedger.Resolved resolved) {
		long from = resolved.gen() == null ? 0L : resolved.gen().seq();
		return new Decision(Mode.REFUSED, null, null, resolved.literalKind(), reason, from,
		 resolved.targetGeneration());
	}

	/**
	 * 判决一条已解析的事实。
	 *
	 * @param resolved     {@link GenerationalFieldLedger#resolve} 的结论
	 * @param fieldDesc    待写字段的描述符（只用于校验，必须与事实登记的 {@code desc} 一致）
	 * @param expectedKind 由 {@code fieldDesc} 定出的字面量档位（调用方用同一张定型表算出）
	 */
	public static Decision decide(GenerationalFieldLedger.Resolved resolved, String fieldDesc,
	                              String expectedKind) {
		if (resolved == null) return OBLIVIOUS;
		switch (resolved.resolution()) {
			case MISS_NO_FACT:
				// 没有证据 ≠ 拒绝：索引缺失只改变成本（§2.3）。
				return OBLIVIOUS;
			case EXACT:
			case CHAINED:
				break;
			case MISS_WINDOW:
				return refused("replay fact generation is no longer addressable (outside the changeset"
				               + " window or its id no longer matches); no substitute generation is used",
				 resolved);
			case REFUSED_DISCONTINUOUS:
				return refused("replay fact is not continuous across the target generation"
				               + " (rawHash changed in between, so the recorded initializer does not"
				               + " describe the target generation)", resolved);
			case REFUSED_LEGACY_VERSION:
				return refused("replay fact was produced under a different algoVersion ("
				               + resolved.algoVersion() + "); the consumer refuses legacy facts instead of"
				               + " normalising them", resolved);
			case REFUSED_NONE_LITERAL:
			default:
				return refused("replay fact carries no cross-generation literal"
				               + " (REINIT tier facts, or literal shape does not match its kind)", resolved);
		}

		String kind = resolved.literalKind();
		if (kind == null || "NONE".equals(kind)) {
			return refused("replay fact carries no cross-generation literal (REINIT tier facts are not"
			               + " cross-generation addressable)", resolved);
		}
		if (expectedKind == null || !expectedKind.equals(kind)) {
			return refused("replay fact literal kind " + kind + " does not match the field descriptor"
			               + " (expected " + expectedKind + ")", resolved);
		}
		String factDesc = resolved.desc();
		if (factDesc != null && !factDesc.isEmpty() && fieldDesc != null && !factDesc.equals(fieldDesc)) {
			return refused("replay fact was recorded for descriptor " + factDesc
			               + ", but the field is now " + fieldDesc, resolved);
		}
		if (!GenerationalFieldLedger.isCanonical(kind, resolved.oldLiteral(), resolved.newLiteral())) {
			return refused("replay fact literals are not in the canonical form of " + kind
			               + "; the consumer never normalises a fact", resolved);
		}
		String algo = resolved.algoVersion();
		if (algo == null || !algo.equals(Changeset.currentAlgoVersion())) {
			return refused("replay fact was produced under a different algoVersion (" + algo
			               + "); the consumer refuses legacy facts instead of normalising them", resolved);
		}
		if (toBootstrapConstant(kind, resolved.oldLiteral(), fieldDesc) == null
		    || toBootstrapConstant(kind, resolved.newLiteral(), fieldDesc) == null) {
			return refused("replay fact literal cannot be decoded for " + kind
			               + " without re-deriving bits from decimal text", resolved);
		}
		return new Decision(Mode.FACT, resolved.oldLiteral(), resolved.newLiteral(), kind, null,
		 resolved.fromGeneration(), resolved.targetGeneration());
	}

	/**
	 * 规范字面量 → 走 {@code KIND_CONDITIONAL_EXPECTED} 的 bootstrap 常量（{@code Number}）。
	 *
	 * <p>{@code Z/B/C/S/I} 一律折叠成 {@code Integer}（{@code HotswapBridge.coerceExpected} 负责按
	 * 字段类型再收窄）；{@code F}/{@code D} 走原始位模式。任何"需要从十进制文本反推位"的形态返回
	 * {@code null}，由调用方 fail-closed。</p>
	 *
	 * @param literalKind 字面量定型
	 * @param literal     规范字面量
	 * @param fieldDesc   目标字段描述符（只接受基本类型；引用类型没有重放通道）
	 * @return bootstrap 常量，或 {@code null}（判不了）
	 */
	public static Number toBootstrapConstant(String literalKind, String literal, String fieldDesc) {
		if (literalKind == null || literal == null || fieldDesc == null || fieldDesc.isEmpty()) return null;
		char descKind = fieldDesc.charAt(0);
		try {
			return switch (literalKind) {
				case "BOOLEAN" -> descKind == 'Z' ? Integer.valueOf(booleanOf(literal)) : null;
				case "BYTE" -> descKind == 'B' ? (Number) Integer.valueOf(Byte.parseByte(literal.trim())) : null;
				case "SHORT" -> descKind == 'S' ? (Number) Integer.valueOf(Short.parseShort(literal.trim())) : null;
				case "CHAR" -> descKind == 'C' ? (Number) Integer.valueOf(charOf(literal)) : null;
				case "INT" -> descKind == 'I' ? (Number) Integer.valueOf(Integer.parseInt(literal.trim())) : null;
				case "LONG" -> descKind == 'J' ? (Number) Long.valueOf(Long.parseLong(literal.trim())) : null;
				case "FLOAT_BITS" -> descKind == 'F'
				 ? (Number) Float.valueOf(Float.intBitsToFloat((int) bits(literal, 8))) : null;
				case "DOUBLE_BITS" -> descKind == 'D'
				 ? (Number) Double.valueOf(Double.longBitsToDouble(bits(literal, 16))) : null;
				default -> null;
			};
		} catch (RuntimeException e) {
			// 形态不合规（十进制配位模式档、越界、空串）：判不了就返回 null，绝不猜。
			return null;
		}
	}

	/** {@code "0x%08x"} / {@code "0x%016x"} → 无符号位模式；其它形态一律抛（交给调用方判 null）。 */
	private static long bits(String literal, int hexDigits) {
		String text = literal.trim();
		if (!text.startsWith("0x") || text.length() != 2 + hexDigits) {
			throw new IllegalArgumentException("not a canonical bit pattern: " + literal);
		}
		return Long.parseUnsignedLong(text.substring(2), 16);
	}

	private static int booleanOf(String literal) {
		String text = literal.trim();
		if ("true".equals(text)) return 1;
		if ("false".equals(text)) return 0;
		// 规范形由 InitFix.literalOf 给出（true/false），这里只接受整数 0/1 作为回退兼容。
		int value = Integer.parseInt(text);
		if (value != 0 && value != 1) throw new IllegalArgumentException("not a boolean: " + literal);
		return value;
	}

	private static int charOf(String literal) {
		if (literal.length() == 1) return literal.charAt(0);
		return Integer.parseInt(literal.trim());
	}
}

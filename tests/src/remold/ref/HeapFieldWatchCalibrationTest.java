package remold.ref;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import remold.RemoldAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code HeapFieldWatch} 的已知答案校准（不变量 C1）。
 *
 * <p>校准问题：这个哨兵是"字段在 redefine 窗口里被置 null"这类结论的唯一观测手段。它自己没被校准过时，
 * {@code nullCount=0} 既可能是"真的干净"，也可能是"根本没看到字段"。本用例用**已知答案**把它钉死：</p>
 * <ol>
 *   <li>12 个实例里人为置 5 个 null → 同一份哨兵、同一条 {@code LibTool.getInstances} 通道，在**同一个
 *       {@code redefineClasses} 前后**都必须恰好报 5；业务终局由实例自己的 {@code probeFieldIsNull()} 复核；</li>
 *   <li>同名同类型字段的对照类一个 null 都没有 → 必须报 0（换无关输入的对照）；</li>
 *   <li>跨切面分母差必须被分成 created / collected，且"非空 → null"只由 {@code newlyNull} 报告；
 *       三个分组恒等式（{@code same+collected=beforeTotal} / {@code same+created=afterTotal} /
 *       {@code persisted+vanished=beforeNull}）必须在同一份读数上同时成立；</li>
 *   <li>字段名写错时必须报 {@code absent=N} 并告警，不得退化成 {@code nullCount=0} 的假阴性；</li>
 *   <li>**改字段表的 redefine**：新增字段 {@code addedProbe} 后存量实例被真实迁移——老字段值原样保留、
 *       分组键（{@code identityHashCode}）在迁移后存活、新字段在迁移后的实例上可读（默认 null），
 *       而哨兵在迁移切面上的读数仍必须精确；</li>
 *   <li>值类型直方图：{@code Object} 字段装 4 种运行期类型 + null 时，各桶计数必须与已知答案逐桶相等
 *       （只数非 null 个数的实现会在这里露馅）；</li>
 *   <li>第二通道（VarHandle / getter）：与反射读逐实例比对的一致数/分歧数必须分别等于已知答案，
 *       分歧必须告警；getter 故意返回 {@code value + "!"} 时分歧数必须等于非 null 实例数。</li>
 * </ol>
 *
 * <p>子 JVM（{@link HeapWatchCalibrationFixture}）用与真机相同的 {@code -Dremold.probe.heapwatch.*} 开关和
 * {@code -javaagent} 启动，并带 {@code -XX:+AllowEnhancedClassRedefinition}：测的是要上真机的那个仪器，
 * 不是测试里的复刻品。运行时是否真的具备增强重定义由子 JVM 自己报告（{@code RESULT capability}），
 * 因此"没做迁移"绝不会被当成"迁移通过"。</p>
 *
 * <h3>校准通道声明（不变量 C1）</h3>
 * <p>{@code total=12/nullCount=5} 这类读数走的是<b>产品通道</b>：{@code HeapFieldWatch.scan} →
 * {@code remold.util.LibTool#getInstances(Class)}（JVMTI 堆遍历，真机上打点的是同一条枚举路径），
 * <b>不是</b>夹具自己的 {@code LIVE} 注册表。三条一起看的证据：</p>
 * <ul>
 *   <li>子 JVM 日志行 {@code [HEAPWATCH] ... loader=loadedClasses/…} 表明目标类由
 *       {@code Instrumentation.getAllLoadedClasses()} 解析（产品路径的第一档），{@code [Lib] Loaded libtool}
 *       表明枚举由 JVMTI 原生库完成；</li>
 *   <li>{@link #readingsComeFromTheProductHeapWalkNotFromTheTestRegistry()} 在注册表之外再放 7 个实例
 *       （其中 3 个字段为 null）：堆遍历必须报 {@code total=19 nullCount=3}，而注册表视角只有 12 个实例、
 *       0 个 null——注册表实现给不出前一组数；</li>
 *   <li>{@code RESULT channel kind=libtool-getInstances libtool=yes} 由子 JVM 自报原生库状态，
 *       加载失败时它会报 {@code no}，该用例随之变红。</li>
 * </ul>
 *
 * <h3>真机现象复刻（{@link HeapWatchPredicateFixture}）</h3>
 * <p>真机：JBR-21 DCEVM 对「含匿名类的 Mixin」做结构热更时，230/244 个
 * {@code LootContextPredicate} 实例的 {@code combinedCondition}（静态类型 {@code Predicate}）被置 null。
 * 夹具把四个变量拆成独立场景，每项单独报 {@code total / newlyNull / agree / conflict}：
 * ①同一类里具名实现 vs 匿名实现两组字段；②④ 见下；③真机同款 G1 与 {@code -Xmx4020m}。任一项
 * {@code newlyNull > 0} 即以 {@code [REPRODUCED]} 失败并带上"重定义前持有的具体实现类"分桶（停损）。</p>
 *
 * <p>子 JVM 的 JDK 由 {@link #javaExecutable()} 决定：优先 {@code -Dremold.heapwatch.childJava(Home)}、
 * 其次 {@code JBR_HOME}/{@code JAVA_HOME_DCEVM}/{@code JAVA_HOME_21_X64}、最后退回本测试 JVM。
 * 构建 JVM 可以是任何 JDK（{@code tests} 源集按 release 8 编译），校准运行时由子 JVM 自报
 * （{@code RESULT childvm ... enhanced=yes|no}）；不具备增强重定义的运行时只走 cold 分支，不会假绿。</p>
 */
class HeapFieldWatchCalibrationTest {

	private static final String TARGET_CLASS = HeapWatchCalibrationFixture.Target.class.getName();
	private static final String CONTROL_CLASS = HeapWatchCalibrationFixture.Control.class.getName();
	private static final String MIXED_CLASS = HeapWatchCalibrationFixture.Mixed.class.getName();
	private static final String ECHO_CLASS = HeapWatchCalibrationFixture.Echo.class.getName();
	private static final String PAIR_CLASS = HeapWatchPredicateFixture.PredicatePair.class.getName();
	/** 每组实例数（与夹具常量一致；写死是为了让断言读起来就是已知答案）。 */
	private static final String GROUP_SIZE = String.valueOf(HeapWatchPredicateFixture.GROUP_SIZE);

	@Test
	void reportsExactlyKNullsAcrossRedefine() throws Exception {
		Child child = run("calibration", TARGET_CLASS, "probeField");
		child.assertExitZero();

		// 前置基线：置 null 的动作确实生效，且 12 个实例都还活着（否则"报 k"没有分母可谈）。
		assertEquals("5", child.result("truth", "nulls", "before-redefine"),
			"precondition: the fixture must really have 5 of 12 instances null before the scan");
		assertEquals("12", child.result("truth", "live", "before-redefine"),
			"precondition: all 12 calibrated instances must be reachable at the first phase");
		assertEquals("v1", child.resultRaw("version", "value", "before-redefine"),
			"precondition: redefine has not run yet at the before-redefine phase");

		// 已知答案：before-redefine 必须报 total=12 nullCount=5。
		assertEquals("12", child.snapshot("total", "before-redefine"), "denominator before redefine");
		assertEquals("5", child.snapshot("nullCount", "before-redefine"),
			"the instrument must report exactly the 5 nulls that were injected (known answer)");
		assertEquals("0", child.snapshot("absent", "before-redefine"), "field is present on every instance");
		assertEquals("0", child.snapshot("unreadable", "before-redefine"), "field must be readable on every instance");

		// 因果链：redefine 真的发生了（方法体常量 v1 -> v2），否则"跨 redefine"这条就没有边界。
		assertEquals("ok", child.resultRaw("redefine", "value", null), "a real redefineClasses must have run");
		assertEquals("v2", child.resultRaw("version", "value", "after-redefine"),
			"the redefine must be visible through the instance's own method after the phase");

		// 同一个 redefine 之后：分母 11（回收 3、新建 2），null 4（被回收的 3 个里有 1 个原本是 null）。
		assertEquals("11", child.snapshot("total", "after-redefine"), "denominator after churn");
		assertEquals("4", child.snapshot("nullCount", "after-redefine"),
			"after the redefine the instrument must still report only the surviving nulls (known answer)");
		assertEquals("4", child.result("truth", "nulls", "after-redefine"),
			"business truth must agree with the instrument (terminal read through the instance's own method)");

		// 余数分组：244/230 这类分母差必须被拆成 created/collected，不得被读成"字段被置空"。
		String diff = child.diff("after-redefine");
		assertEquals("9", diffField(diff, "same"), "9 instances survive both phases: " + diff);
		assertEquals("2", diffField(diff, "created"), "2 instances were created between phases: " + diff);
		assertEquals("3", diffField(diff, "collected"), "3 instances were collected between phases: " + diff);
		assertEquals("4", diffField(diff, "persisted"), "the 4 surviving nulls persist: " + diff);
		assertEquals("1", diffField(diff, "vanished"),
			"one null left the heap with its instance (benign, not a field wipe): " + diff);
		assertEquals("0", diffField(diff, "newlyNull"),
			"nothing went non-null -> null across the redefine: " + diff);

		// 反面对照：人为把存活实例置空后，分组必须报 newlyNull=1 —— 否则上面的 newlyNull=0 只是空转。
		assertEquals("5", child.snapshot("nullCount", "after-field-patch"),
			"the deliberately wiped instance must be counted at the last phase");
		assertEquals("1", diffField(child.diff("after-field-patch"), "newlyNull"),
			"the grouping must flag exactly the instance that went non-null -> null: "
				+ child.diff("after-field-patch"));
		assertEquals("0", diffField(child.diff("after-field-patch"), "vanished"),
			"no instance left the heap between the last two phases: " + child.diff("after-field-patch"));
	}

	@Test
	void controlClassWithSameFieldReportsZero() throws Exception {		// 换无关输入的对照：同一个字段名、同一条通道、同样的可空引用字段，只是这个类一个 null 都没有。
		Child child = run("control", CONTROL_CLASS, "probeField");
		child.assertExitZero();
		assertEquals("9", child.snapshot("total", "before-redefine"), "9 control instances must be enumerated");
		assertEquals("0", child.snapshot("nullCount", "before-redefine"),
			"the control class must report 0 nulls — a non-zero count here would mean the instrument counts"
				+ " something other than the configured class/field");
		assertEquals("0", child.snapshot("absent", "before-redefine"), "control field is present");
	}

	@Test
	void missingFieldIsReportedLoudlyInsteadOfZero() throws Exception {
		// 假阴性防线：字段名写错时，"看不到"绝不能伪装成"干净"。
		Child child = run("absent", TARGET_CLASS, "noSuchProbeField");
		child.assertExitZero();
		assertEquals("12", child.snapshot("total", "before-redefine"), "12 instances must still be enumerated");
		assertEquals("0", child.snapshot("nullCount", "before-redefine"),
			"an absent field must not be reported as a null hit");
		assertEquals("12", child.snapshot("absent", "before-redefine"),
			"an absent field must be reported as absent=N for every instance, not swallowed");
		assertTrue(child.outputContains("[HEAPWATCH] field cannot be observed on 12 instance(s)"),
			"the instrument must warn that nullCount is not evidence when the field is unobservable;"
				+ " output was:\n" + child.output);
	}

	@Test
	void instrumentLogLinesAreActuallyEmitted() throws Exception {
		// 人读日志是运维唯一的观测面：结构化读数为真但日志没打，真机上依旧等于没校准。
		Child child = run("calibration", TARGET_CLASS, "probeField");
		child.assertExitZero();
		assertTrue(child.outputContains("[HEAPWATCH] phase=before-redefine"), "the phase log line must be emitted");
		assertTrue(child.outputContains("total=12 nullCount=5"), "the phase log line must carry the known answer");
		assertTrue(child.outputContains("[HEAPWATCH-DIFF] before=before-redefine after=after-redefine"),
			"the cross-phase grouping line must be emitted");
	}

	@Test
	void readingsAfterRedefineComeFromTheCurrentClassVersion() throws Exception {
		// JBR/DCEVM 的 redefine 会把**静态字段里**持有的 Class 改写成旧版本"影子类"（字段元数据是旧的）。
		// 哨兵把目标类缓存在静态字段里，因此不归一就会一直读旧字段表：nullCount 变假阴性、第二通道全线不可用。
		Child child = run("calibration", TARGET_CLASS, "probeField");
		child.assertExitZero();
		assertEquals("11", child.groupValue("after-redefine", "channels", "agree"),
			"the second channel must still resolve after the redefine (a stale shadow Class cannot): "
				+ child.groupText("after-redefine", "channels"));
		assertEquals("0", child.groupValue("after-redefine", "channels", "conflict"),
			"the second channel and the reflective read must still agree after the redefine: "
				+ child.groupText("after-redefine", "channels"));
		assertEquals("4", child.groupValue("after-redefine", "valueTypes", "null"),
			"the histogram after the redefine must describe the surviving 4 nulls: "
				+ child.groupText("after-redefine", "valueTypes"));
		assertEquals("7", child.groupValue("after-redefine", "valueTypes", "java.lang.String"),
			"the histogram after the redefine must describe the 7 non-null String values: "
				+ child.groupText("after-redefine", "valueTypes"));
		if ("yes".equals(child.resultRaw("capability", "enhanced", null))) {
			// 这条只在会改写静态 Class 引用的运行时上有意义；标准 HotSpot 不产生影子类。
			assertTrue(child.outputContains("[HEAPWATCH] cached Class for " + TARGET_CLASS + " was a stale shadow"),
				"the probe must detect and refresh the stale shadow Class written by the redefine,"
					+ " otherwise every post-redefine reading describes the old field table; output was:\n"
					+ child.output);
		}
	}

	@Test
	void valueTypeHistogramCountsEveryTypeInsteadOfJustNonNulls() throws Exception {
		// 值类型直方图：Object 字段里 3 个 null + 4 String + 2 Integer + 2 Long + 1 ArrayList。
		// 只数"非 null 个数"的实现会在这里露馅：它只能给出一个数，给不出逐类型计数。
		Child child = run("histogram", MIXED_CLASS, "probeField");
		child.assertExitZero();
		assertEquals("12", child.snapshot("total", "before-redefine"), "12 mixed instances must be enumerated");
		assertEquals("3", child.snapshot("nullCount", "before-redefine"), "known answer: 3 nulls");
		assertEquals("3", child.groupValue("before-redefine", "valueTypes", "null"),
			"the histogram must carry the null bucket: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("4", child.groupValue("before-redefine", "valueTypes", "java.lang.String"),
			"known answer: 4 String values: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("2", child.groupValue("before-redefine", "valueTypes", "java.lang.Integer"),
			"known answer: 2 Integer values: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("2", child.groupValue("before-redefine", "valueTypes", "java.lang.Long"),
			"known answer: 2 Long values: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("1", child.groupValue("before-redefine", "valueTypes", "java.util.ArrayList"),
			"known answer: 1 ArrayList value: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("12", child.groupSum("before-redefine", "valueTypes"),
			"every enumerated instance must land in exactly one histogram bucket: "
				+ child.groupText("before-redefine", "valueTypes"));
		// 第二通道：字段句柄通道在这台机器上应全程与反射一致。
		assertEquals("12", child.groupValue("before-redefine", "channels", "agree"),
			"the field-handle channel must agree with the reflective read on every instance: "
				+ child.groupText("before-redefine", "channels"));
		assertEquals("0", child.groupValue("before-redefine", "channels", "conflict"),
			"no channel disagreement is expected here: " + child.groupText("before-redefine", "channels"));
	}

	@Test
	void secondChannelDisagreementIsCountedAndWarned() throws Exception {
		// 对照：getter 通道返回 value + "!"，因此非 null 的 7 个实例必须被判为分歧，null 的 5 个一致。
		Child child = run("channel-conflict", ECHO_CLASS, "probeField", "probeFieldEcho");
		child.assertExitZero();
		assertEquals("12", child.snapshot("total", "before-redefine"), "12 echo instances must be enumerated");
		assertEquals("5", child.snapshot("nullCount", "before-redefine"),
			"precondition: 5 of the 12 instances are null");
		assertEquals("5", child.groupValue("before-redefine", "channels", "agree"),
			"only the 5 null instances can agree (null vs null): "
				+ child.groupText("before-redefine", "channels"));
		assertEquals("7", child.groupValue("before-redefine", "channels", "conflict"),
			"the 7 non-null instances must be reported as channel conflicts: "
				+ child.groupText("before-redefine", "channels"));
		assertEquals("0", child.groupValue("before-redefine", "channels", "unavailable"),
			"both channels resolve here, so none may be reported as unavailable");
		assertTrue(child.outputContains("[HEAPWATCH] second channel disagrees with the reflective read on 7 of 12"),
			"a channel disagreement must be warned about, not swallowed; output was:\n" + child.output);
	}

	/**
	 * 改字段表的 redefine：存量实例真被迁移、分组键存活、三个分组恒等式在同一次迁移切面上同时成立。
	 *
	 * <p>标准 HotSpot 不允许 schema 变化，子 JVM 会明确报 {@code RESULT capability enhanced=no}；本用例在此
	 * 情况下**不会**假装迁移成功——它要求运行时自报能力，能力为 yes 时迁移断言全部生效。</p>
	 */
	@Test
	void schemaChangingRedefineMigratesInstancesAndKeepsGroupingKeys() throws Exception {
		Child child = run("migrate", TARGET_CLASS, "probeField");
		child.assertExitZero();
		String schema = child.resultRaw("schema", "value", null);
		if (!"yes".equals(child.resultRaw("capability", "enhanced", null))) {
			// 标准 HotSpot 不允许字段表变化：迁移窗口在这台机器上根本不存在。纪律是**明确瘦身**——
			// 断言子 JVM 自报 cold 并给出原因，然后只校准"只改方法体"的 redefine 切面，
			// 绝不把"没做迁移"读成"迁移通过"。
			assertEquals("cold", schema,
				"a runtime without enhanced redefinition must report the schema redefine as cold,"
					+ " not silently pretend the field table changed; output was:\n" + child.output);
			String coldReason = child.resultRaw("schema", "reason", null);
			assertTrue(coldReason != null && !coldReason.isEmpty(),
				"the cold branch must say why the migration could not run; output was:\n" + child.output);
			// snapshot(field, phase)：两个参数顺序不能颠倒，否则冷分支永远在找 phase=total 的行。
			assertEquals("12", child.snapshot("total", "after-redefine"),
				"cold branch: the denominator must still be calibrated on the method-only redefine");
			assertEquals("5", child.snapshot("nullCount", "after-redefine"),
				"cold branch: the known answer must still hold on the method-only redefine");
			assertEquals("ok", child.result("identity", "value", "after-redefine"),
				"cold branch: the grouping identities must still hold");
			assertEquals("12", child.result("identity", "same", "after-redefine"),
				"cold branch: the same 12 instances must still be matched across the redefine");
			return;
		}
		assertEquals("ok", schema,
			"the field table must really change (addedProbe): " + child.resultRaw("schema", "reason", null));

		// 前置基线：迁移前 12 个实例、5 个 null；否则后面的"迁移后仍是 5"没有分母可谈。
		assertEquals("12", child.snapshot("total", "before-redefine"), "denominator before the migration");
		assertEquals("5", child.snapshot("nullCount", "before-redefine"), "known answer before the migration");
		assertEquals("5", child.groupValue("before-redefine", "valueTypes", "null"),
			"histogram null bucket before the migration: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("7", child.groupValue("before-redefine", "valueTypes", "java.lang.String"),
			"histogram String bucket before the migration: " + child.groupText("before-redefine", "valueTypes"));
		assertEquals("12", child.groupValue("before-redefine", "channels", "agree"),
			"the field-handle channel must agree on every instance before the migration: "
				+ child.groupText("before-redefine", "channels"));

		// 迁移真的发生了（不经哨兵的业务终局）：老值原样、身份键存活、新字段可读。
		assertEquals("true", child.result("migration", "field", null),
			"the redefined class must carry the added field");
		assertEquals("12/12", child.result("migration", "oldValuesIntact", null),
			"migrated instances must keep their old field values (migration, not a fresh allocation)");
		assertEquals("12/12", child.result("migration", "identityKeysSurvived", null),
			"identityHashCode is the grouping key: if it changed, grouping would read as created+collected");
		assertEquals("12", child.result("migration", "addedFieldNulls", null),
			"every migrated instance must expose the added field with its default value");

		// 迁移切面上的读数：分母、null 数、字段可观测性都必须仍然精确。
		assertEquals("12", child.snapshot("total", "after-redefine"), "denominator after the migration");
		assertEquals("5", child.snapshot("nullCount", "after-redefine"),
			"the migrating redefine must not change the watched field's null count");
		assertEquals("0", child.snapshot("absent", "after-redefine"),
			"the watched field must still be present on every migrated instance");
		assertEquals("5", child.result("truth", "nulls", "after-redefine"),
			"business truth must agree with the instrument after the migration");
		assertEquals("12", child.groupValue("after-redefine", "channels", "agree"),
			"the second channel must agree on the migrated instances as well: "
				+ child.groupText("after-redefine", "channels"));

		// 分组恒等式：迁移不能让身份键漂移，否则 same/created/collected 会凭空出现余数。
		assertEquals("ok", child.result("identity", "value", "after-redefine"),
			"the three grouping identities must hold across the migration");
		assertEquals("12", child.result("identity", "same", "after-redefine"),
			"all 12 instances survive the migration under the same identity key");
		assertEquals("0", child.result("identity", "created", "after-redefine"),
			"no instance may be counted as created by a migration");
		assertEquals("0", child.result("identity", "collected", "after-redefine"),
			"no instance may be counted as collected by a migration");
		assertEquals("5", child.result("identity", "persisted", "after-redefine"),
			"the 5 nulls persist through the migration");
		assertEquals("0", child.result("identity", "vanished", "after-redefine"),
			"no null instance left the heap here");
		assertEquals("0", child.result("identity", "newlyNull", "after-redefine"),
			"a migration must not turn a non-null field into null");
		assertEquals("12", child.result("identity", "beforeTotal", "after-redefine"),
			"the identity line must expose the before-total it was checked against");
		assertEquals("12", child.result("identity", "afterTotal", "after-redefine"),
			"the identity line must expose the after-total it was checked against");

		// 新增字段：迁移补齐后是默认 null —— 同一条仪器通道读它必须报 12，而不是 absent。
		assertEquals("12", child.result("addedfield", "total", null),
			"the added field must be enumerable on the migrated instances");
		assertEquals("12", child.result("addedfield", "nullCount", null),
			"the added field must read as default null on every migrated instance");
		assertEquals("0", child.result("addedfield", "absent", null),
			"the added field must be observable, not reported absent, after the migration");
	}

	/**
	 * 校准通道证明：读数是 JVMTI 堆遍历（产品通道），不是夹具注册表。
	 *
	 * <p>做法是最小反例：注册表里放 12 个实例、字段全非 null；另外 7 个实例只挂在 {@code SIDECAR} 上
	 * （任何注册表/强引用列表都看不见它们），其中 3 个字段为 null。堆遍历必须报 {@code total=19 nullCount=3}，
	 * 而"照着注册表数"只能给出 {@code 12 / 0}——两者不可能同时成立，因此这个断言能区分两条通道。</p>
	 */
	@Test
	void readingsComeFromTheProductHeapWalkNotFromTheTestRegistry() throws Exception {
		List<String> extra = new ArrayList<String>();
		extra.add("-Dremold.probe.heapwatch=true");
		extra.add("-Dremold.probe.heapwatch.class=" + PAIR_CLASS);
		extra.add("-Dremold.probe.heapwatch.field=namedCondition");
		Child child = spawn(HeapWatchPredicateFixture.class, "channel-heapwalk", extra);
		child.assertExitZero();

		assertEquals("yes", child.result("channel", "libtool", null),
			"the readings must come from the product channel (LibTool.getInstances, JVMTI heap walk);"
				+ " a failed native load would turn every count into a fabrication; output was:\n" + child.output);
		assertEquals("12", child.result("channel", "registry", null), "the fixture registry holds 12 instances");
		assertEquals("7", child.result("channel", "sidecar", null),
			"7 more instances are reachable only through the sidecar holder");
		assertEquals("0", child.result("truth", "registryNulls", "before-redefine"),
			"precondition: every registered instance has a non-null field, so a registry-based reader would report 0");
		assertEquals("3", child.result("truth", "sidecarNulls", "before-redefine"),
			"precondition: exactly 3 unregistered instances hold null");
		assertEquals("19", child.result("item-named", "total", "before"),
			"the heap walk must enumerate registry + sidecar (12 + 7); a registry-based count would stop at 12;"
				+ " output was:\n" + child.output);
		assertEquals("3", child.result("item-named", "nullCount", "before"),
			"the heap walk must see the 3 nulls that live outside the fixture registry");
		assertEquals("19", child.result("item-named", "agree", "before"),
			"the second channel must resolve on the unregistered instances as well");
		assertTrue(child.outputContains("loader=loadedClasses/"),
			"the product path resolves the class through Instrumentation.getAllLoadedClasses();"
				+ " output was:\n" + child.output);
	}

	/**
	 * 复刻变量①：具名实现 vs 匿名实现两组 {@code Predicate} 字段，在同一次结构重定义切面上的读数。
	 *
	 * <p>两组共用同一次 redefine、同一个类、同样非 null：唯一差别是字段值是不是匿名类实例。因此
	 * "只有含匿名引用的一组被置 null"可以直接归因到匿名引用；"两组都不变"则排除它。</p>
	 */
	@Test
	void predicateFieldGroupsStayNonNullAcrossSchemaRedefineRegardlessOfAnonymousValues() throws Exception {
		Child child = spawn(HeapWatchPredicateFixture.class, "predicate-groups", new ArrayList<String>());
		child.assertExitZero();

		// 前置基线：两组各 120 个实例、字段全非 null（排除"原本就是空"的假阳性）。
		assertGroupReadings(child, "named", GROUP_SIZE);
		assertGroupReadings(child, "anon", GROUP_SIZE);
		assertEquals("0", child.result("truth", "namedNulls", "before-redefine"),
			"precondition: every namedCondition field is non-null before the redefine");
		assertEquals("0", child.result("truth", "anonNulls", "before-redefine"),
			"precondition: every anonCondition field is non-null before the redefine");

		if (!enhanced(child)) {
			// 没有增强重定义的运行时不允许字段表变化：子 JVM 必须自报 cold（不把"没做迁移"读成"迁移通过"）。
			assertEquals("unchanged", child.result("truth", "schema", "after-redefine"),
				"a runtime without enhanced redefinition must report the schema redefine as unchanged;"
					+ " output was:\n" + child.output);
			return;
		}
		assertEquals("changed", child.result("truth", "schema", "after-redefine"),
			"the field table must really change (addedProbe): " + child.output);
		assertEquals(GROUP_SIZE + "/" + GROUP_SIZE, child.result("truth", "usable", "after-redefine"),
			"every migrated instance must still answer through both predicates (a null field would NPE here)");
		assertNoFieldWipe(child, "named", "namedCondition");
		assertNoFieldWipe(child, "anon", "anonCondition");

		// 对照：故意置空 1 个存活实例后，跨切面分组必须报 newlyNull=1，且分桶必须点名"重定义前持有的实现类"。
		assertEquals("1", child.result("item-named", "newlyNull", "control"),
			"the grouping must flag the instance that was deliberately wiped: " + child.output);
		assertEquals("1", child.result("item-anon", "newlyNull", "control"),
			"the grouping must flag the deliberately wiped anonymous-condition instance: " + child.output);
		String namedType = child.result("control", "type", "named");
		String anonType = child.result("control", "type", "anon");
		assertTrue(namedType.contains("NamedCondition"),
			"the named bucket must name the concrete pre-redefine implementation class, got " + namedType);
		assertTrue(anonType.indexOf('$') >= 0,
			"the anonymous bucket must name a synthetic anonymous class, got " + anonType);
		assertEquals("1", child.resultMatching("bucket", "count",
				"phase", "control", "field", "namedCondition", "type", namedType),
			"the pre-redefine implementation class of the wiped field must be recoverable per bucket (invariant C1)");
		assertEquals("1", child.resultMatching("bucket", "count",
				"phase", "control", "field", "anonCondition", "type", anonType),
			"the anonymous pre-redefine implementation class must be recoverable per bucket (invariant C1)");
	}

	/**
	 * 复刻变量②：≥3 个同时存活的匿名类实例，各自被旁观实例引用；结构重定义它们的外层类后必须全部还在。
	 */
	@Test
	void multipleLiveAnonymousClassInstancesSurviveStructuralRedefineOfTheirEnclosers() throws Exception {
		Child child = spawn(HeapWatchPredicateFixture.class, "predicate-anon", new ArrayList<String>());
		child.assertExitZero();

		assertEquals("4", child.result("anon", "instances", null),
			"at least 3 anonymous class instances must be alive at the same time");
		assertEquals("4", child.result("anon", "distinctClasses", null),
			"the live anonymous instances must come from distinct anonymous classes");
		assertEquals("4", child.result("item-anonRef", "total", "before"),
			"the 4 bystanders must be enumerated before the redefine");
		assertEquals("0", child.result("item-anonRef", "nullCount", "before"),
			"precondition: every bystander really references an anonymous instance");
		assertEquals("4", child.result("item-anonRef", "agree", "before"),
			"the second channel must resolve on the anonymous references");
		if (!enhanced(child)) {
			assertEquals("unchanged", child.result("schema", "all", null),
				"a runtime without enhanced redefinition must report the schema redefines as unchanged;"
					+ " output was:\n" + child.output);
			return;
		}
		assertEquals("changed", child.result("schema", "all", null),
			"all four enclosing classes must be structurally redefined: " + child.output);
		assertNoFieldWipe(child, "anonRef", "anonRef");
		assertEquals("4/4", child.result("anonAlive", "alive", null),
			"every live anonymous instance must survive the structural redefine of its enclosing class");
		assertEquals("4/4", child.result("anonAlive", "viaBystanders", null),
			"every bystander must still reference a usable anonymous instance after the redefine");
	}

	/**
	 * 复刻变量③：真机同款 G1 与 {@code -Xmx4020m}（不缩小）。读数必须与默认堆那轮完全一致——
	 * 收集器/堆大小不得改变仪器看到的东西。
	 */
	@Test
	void realG1HeapWithMachineSizeKeepsReadingsExact() throws Exception {
		List<String> extra = new ArrayList<String>();
		extra.add("-XX:+UseG1GC");
		extra.add("-Xmx4020m");
		Child child = spawn(HeapWatchPredicateFixture.class, "predicate-g1", extra);
		child.assertExitZero();

		assertEquals("yes", child.result("heap", "g1arg", "g1"),
			"the child JVM must be started with -XX:+UseG1GC (real-machine GC)");
		assertEquals("yes", child.result("heap", "g1", "g1"),
			"the child must actually run on the G1 collector, not silently fall back; output was:\n" + child.output);
		assertEquals("4215275520", child.result("heap", "max", "g1"),
			"-Xmx4020m must be in effect (real machine log: 4020m; deliberately not scaled down here)");
		// 与 predicate-groups 同体：读数（含 newlyNull=0）必须一致。
		assertGroupReadings(child, "named", GROUP_SIZE);
		assertGroupReadings(child, "anon", GROUP_SIZE);
	}

	/**
	 * 复刻变量④：重定义期间持续分配 + 穿过字段读 {@code Predicate}。读空就是 NPE，计数落在
	 * {@code npe=}/{@code nullReads=} 上——不是靠"日志里没有"推断。
	 */
	@Test
	void concurrentAllocationAndReadDuringRedefineSeeNoNull() throws Exception {
		Child child = spawn(HeapWatchPredicateFixture.class, "predicate-concurrent", new ArrayList<String>());
		child.assertExitZero();

		assertTrue(Long.parseLong(child.result("concurrent", "reads", null)) > 0,
			"the reader threads must really have run; output was:\n" + child.output);
		assertTrue(Long.parseLong(child.result("concurrent", "allocations", null)) > 0,
			"the worker threads must really have allocated new instances; output was:\n" + child.output);
		assertTrue(Long.parseLong(child.result("concurrent", "readsAcrossRedefine", null)) > 0,
			"reads must overlap the redefine window, otherwise this case proves nothing");
		assertEquals("0", child.result("concurrent", "errors", null),
			"no non-NPE failure may be thrown by the readers; output was:\n" + child.output);
		if (!"0".equals(child.result("concurrent", "nullReads", null))) {
			fail("[REPRODUCED] a concurrent read observed a null Predicate field during the redefine: "
				+ child.result("concurrent", "nullReads", null) + " null read(s), "
				+ child.result("concurrent", "npe", null) + " NPE(s); stop-loss: report this reading instead of"
				+ " continuing; pre-redefine implementation classes: " + bucketText(child, "namedCondition")
				+ " / " + bucketText(child, "anonCondition") + "; output was:\n" + child.output);
		}
		assertEquals("0", child.result("concurrent", "npe", null),
			"a concurrent read must not throw NullPointerException; output was:\n" + child.output);

		// 重定义之后的存量读数：分母含并发线程新建并保留的实例，因此只钉计数关系，不钉固定分母。
		assertEquals("0", child.result("item-named", "nullCount", "after"),
			"no surviving instance may have a null namedCondition after the concurrent redefine");
		assertEquals("0", child.result("item-anon", "nullCount", "after"),
			"no surviving instance may have a null anonCondition after the concurrent redefine");
		assertEquals("0", child.result("item-named", "conflict", "after"),
			"the second channel must still agree after concurrent churn");
		assertEquals("ok", child.result("item-named", "identities", "after"),
			"the grouping identities must hold across the concurrent redefine");
		assertNoFieldWipe(child, "named", "namedCondition");
		assertNoFieldWipe(child, "anon", "anonCondition");
	}

	// ------------------------------------------------------------------------------------------------
	// 复刻场景的共用断言
	// ------------------------------------------------------------------------------------------------

	private static boolean enhanced(Child child) {
		return "yes".equals(child.resultRaw("childvm", "enhanced", null));
	}

	/** 一组字段的 known-answer 读数：分母、null 数、第二通道、分组恒等式。 */
	private static void assertGroupReadings(Child child, String group, String total) {
		assertEquals(total, child.result("item-" + group, "total", "before"),
			group + ": denominator before the redefine (ground truth for the heap walk)");
		assertEquals("0", child.result("item-" + group, "nullCount", "before"),
			group + ": precondition — no field may be null before the redefine");
		assertEquals("0", child.result("item-" + group, "absent", "before"),
			group + ": the field must be observable on every instance");
		assertEquals(total, child.result("item-" + group, "agree", "before"),
			group + ": the second channel must agree on every instance before the redefine");
		assertEquals("0", child.result("item-" + group, "conflict", "before"),
			group + ": no channel conflict before the redefine");
		assertEquals(total, child.result("item-" + group, "total", "after"),
			group + ": denominator after the redefine");
		assertEquals(total, child.result("item-" + group, "afterTotal", "after"),
			group + ": the grouping line must be computed on the same denominator");
		assertEquals("ok", child.result("item-" + group, "identities", "after"),
			group + ": the three grouping identities must hold across the redefine");
		assertNoFieldWipe(child, group, "namedCondition".equals(fieldOf(group)) ? "namedCondition" : "anonCondition");
		assertEquals("0", child.result("item-" + group, "nullCount", "after"),
			group + ": the structural redefine must not change the watched field's null count");
		assertEquals(total, child.result("item-" + group, "agree", "after"),
			group + ": the second channel must still agree on every migrated instance");
		assertEquals("0", child.result("item-" + group, "conflict", "after"),
			group + ": no channel conflict may appear after the redefine");
	}

	private static String fieldOf(String group) {
		return "named".equals(group) ? "namedCondition" : "anonCondition";
	}

	/** 停损：跨切面 "非空 → null" 必须为 0；不为 0 就以 {@code [REPRODUCED]} 失败并带上实现类分桶。 */
	private static void assertNoFieldWipe(Child child, String group, String field) {
		String newlyNull = child.result("item-" + group, "newlyNull", "after");
		if (!"0".equals(newlyNull)) {
			fail("[REPRODUCED] " + newlyNull + " instance(s) of " + field + " went non-null -> null across the"
				+ " structural redefine; stop-loss: report this reading instead of continuing."
				+ " Pre-redefine implementation classes (buckets): " + bucketText(child, field)
				+ "; output was:\n" + child.output);
		}
	}

	private static String bucketText(Child child, String field) {
		StringBuilder sb = new StringBuilder();
		for (String line : child.output.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.startsWith("RESULT bucket")) {
				continue;
			}
			if (trimmed.contains("field=" + field)) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(trimmed.substring("RESULT bucket ".length()));
			}
		}
		return sb.length() == 0 ? "none" : sb.toString();
	}

	// ------------------------------------------------------------------------------------------------
	// 子 JVM 驱动
	// ------------------------------------------------------------------------------------------------
	private static Child run(String scenario, String className, String fieldName) throws Exception {
		return run(scenario, className, fieldName, null);
	}

	private static Child run(String scenario, String className, String fieldName, String getterName)
		throws Exception {
		List<String> extra = new ArrayList<String>();
		extra.add("-Dremold.probe.heapwatch=true");
		extra.add("-Dremold.probe.heapwatch.class=" + className);
		extra.add("-Dremold.probe.heapwatch.field=" + fieldName);
		if (getterName != null) {
			extra.add("-Dremold.probe.heapwatch.getter=" + getterName);
		}
		return spawn(HeapWatchCalibrationFixture.class, scenario, extra);
	}

	/**
	 * 起一个子 JVM 跑指定夹具场景。
	 *
	 * <p>子 JVM 与真机同形：{@code -javaagent} + 增强重定义开关 + 产品通道的原生库路径；
	 * {@code extraJvmArgs} 用来叠加场景专属参数（堆哨兵开关、G1/堆上限）。</p>
	 */
	private static Child spawn(Class<?> fixture, String scenario, List<String> extraJvmArgs) throws Exception {
		File java = javaExecutable();
		File agentJar = agentJar();
		assertNotNull(agentJar, "the agent jar must be built before this test runs (:tests:test depends on"
			+ " :core:shadowJar); without it the instrument cannot be calibrated at all");
		List<String> command = new ArrayList<String>();
		command.add(java.getAbsolutePath());
		command.add("-javaagent:" + agentJar.getAbsolutePath());
		command.addAll(extraJvmArgs);
		// 增强重定义开关：字段表变化的 redefine（实例迁移）需要它。标准 HotSpot 会忽略这两个参数，
		// 子 JVM 自己报告 capability，测试据此区分"迁移真的发生了"与"这个运行时做不了 schema 变化"。
		command.add("-XX:+IgnoreUnrecognizedVMOptions");
		command.add("-XX:+AllowEnhancedClassRedefinition");
		String nativeLib = System.getProperty("remold.path.libtool");
		if (nativeLib != null) {
			command.add("-Dremold.path.libtool=" + nativeLib);
		}
		if (majorJavaVersion() >= 9) {
			command.add("--add-exports");
			command.add("java.base/jdk.internal.misc=ALL-UNNAMED");
		}
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(fixture.getName());
		command.add(scenario);

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		StringBuilder output = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
		String line;
		while ((line = reader.readLine()) != null) {
			output.append(line).append('\n');
		}
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail("calibration child JVM did not finish within 300s; output was:\n" + output);
		}
		dumpChildOutput(scenario, output.toString());
		return new Child(scenario, process.exitValue(), output.toString());
	}

	/**
	 * 可选：把子 JVM 的完整输出落盘（{@code -Dremold.heapwatch.dumpDir=<dir>} 或环境变量
	 * {@code REMOLD_HEAPWATCH_DUMP_DIR}；Gradle 的 test 任务不透传 {@code -D}，但会透传环境变量），
	 * 便于人工核对真机上要看的 {@code [HEAPWATCH]} 日志。默认不落盘。
	 */
	private static void dumpChildOutput(String scenario, String output) {
		String dir = System.getProperty("remold.heapwatch.dumpDir");
		if (dir == null || dir.trim().isEmpty()) {
			dir = System.getenv("REMOLD_HEAPWATCH_DUMP_DIR");
		}
		if (dir == null || dir.trim().isEmpty()) {
			return;
		}
		try {
			File target = new File(dir, "heapwatch-calibration-" + scenario + ".log");
			File parent = target.getParentFile();
			if (parent != null && !parent.isDirectory()) {
				parent.mkdirs();
			}
			java.io.Writer writer = new java.io.OutputStreamWriter(
				new java.io.FileOutputStream(target), "UTF-8");
			try {
				writer.write(output);
			} finally {
				writer.close();
			}
		} catch (Exception e) {
			// 诊断落盘失败不得影响校准结论。
			System.out.println("[heapwatch-calibration] cannot dump child output: " + e);
		}
	}

	/**
	 * 子 JVM 的 {@code java}。
	 *
	 * <p>构建 JVM 与校准运行时不是一回事：{@code test} 任务跑在构建 JDK 上（本仓库是 JDK 25，panama 源集需要
	 * 非 preview 的 {@code MemorySegment}），而校准必须在 JBR-21 DCEVM 上跑。因此这里按项目既有约定解析
	 * 校准运行时：{@code -Dremold.heapwatch.childJava(Home)} → {@code JBR_HOME} → {@code JAVA_HOME_DCEVM}
	 * → {@code JAVA_HOME_21_X64} → 本测试 JVM 的 {@code java.home}（与 {@code tests/build.gradle} 里
	 * DCEVM 探针的解析顺序一致）。子 JVM 自报 {@code RESULT childvm ... enhanced=}，所以"跑错运行时"
	 * 不会被伪装成"迁移通过"。</p>
	 */
	private static File javaExecutable() {
		String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
		String explicit = System.getProperty("remold.heapwatch.childJava");
		if (explicit != null && !explicit.trim().isEmpty()) {
			File file = new File(explicit.trim());
			if (file.isFile()) {
				return file;
			}
		}
		String home = System.getProperty("remold.heapwatch.childJavaHome");
		if (isJavaHome(home, exe)) {
			return new File(new File(home.trim(), "bin"), exe);
		}
		String[] envHomes = {"JBR_HOME", "JAVA_HOME_DCEVM", "JAVA_HOME_21_X64"};
		for (String name : envHomes) {
			String value = System.getenv(name);
			if (isJavaHome(value, exe)) {
				return new File(new File(value.trim(), "bin"), exe);
			}
		}
		return new File(new File(System.getProperty("java.home"), "bin"), exe);
	}

	private static boolean isJavaHome(String home, String exe) {
		return home != null && !home.trim().isEmpty()
			&& new File(new File(home.trim(), "bin"), exe).isFile();
	}

	private static int majorJavaVersion() {
		String spec = System.getProperty("java.specification.version", "8");
		if (spec.startsWith("1.")) {
			return Integer.parseInt(spec.substring(2));
		}
		int dot = spec.indexOf('.');
		return Integer.parseInt(dot < 0 ? spec : spec.substring(0, dot));
	}

	/**
	 * 定位 {@code -javaagent} 用的 jar：{@code :tests:test} 的 classpath 上是 core 的 raw jar，
	 * 同目录下就是 shadow 出来的 {@code remold-agent.jar}。
	 */
	private static File agentJar() {
		String override = System.getProperty("remold.heapwatch.agentJar");
		if (override != null && !override.trim().isEmpty()) {
			File file = new File(override);
			return file.isFile() ? file : null;
		}
		File sibling = new File(codeSourceDirOfAgentClasses(), "remold-agent.jar");
		if (sibling.isFile()) {
			return sibling;
		}
		File fromWorkingDir = new File("..", "core/build/libs/remold-agent.jar").getAbsoluteFile();
		return fromWorkingDir.isFile() ? fromWorkingDir : null;
	}

	private static File codeSourceDirOfAgentClasses() {
		try {
			File here = new File(RemoldAgent.class.getProtectionDomain().getCodeSource()
				.getLocation().toURI());
			return here.isDirectory() ? here : here.getParentFile();
		} catch (Exception e) {
			throw new IllegalStateException("cannot locate the remold agent classes/jar: " + e, e);
		}
	}

	/** 一次子 JVM 运行的输出与退出码。 */
	private static final class Child {
		final String scenario;
		final int exitCode;
		final String output;

		Child(String scenario, int exitCode, String output) {
			this.scenario = scenario;
			this.exitCode = exitCode;
			this.output = output;
		}

		void assertExitZero() {
			if (exitCode != 0) {
				fail("calibration child JVM (" + scenario + ") exited " + exitCode + "; output was:\n" + output);
			}
		}

		boolean outputContains(String needle) {
			return output.contains(needle);
		}

		/** {@code RESULT snapshot phase=<phase> <field>=<value> ...} 中的字段值。 */
		String snapshot(String field, String phase) {
			return result("snapshot", field, phase);
		}

		/** {@code DIFF phase=<phase> <grouping line>} 里的分组行。 */
		String diff(String phase) {
			String prefix = "DIFF phase=" + phase + " ";
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.startsWith(prefix)) {
					return trimmed.substring(prefix.length());
				}
			}
			fail("no cross-phase grouping line was produced for phase=" + phase + "; output was:\n" + output);
			return null;
		}

		/** {@code RESULT snapshot phase=<phase> ... <group>{k=v ...}} 里整段分组文本（含花括号）。 */
		String groupText(String phase, String group) {
			String prefix = "RESULT snapshot phase=" + phase + " ";
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.startsWith(prefix)) {
					continue;
				}
				int at = trimmed.indexOf(group + "{", prefix.length());
				if (at < 0) {
					continue;
				}
				int close = trimmed.indexOf('}', at);
				if (close < 0) {
					continue;
				}
				return trimmed.substring(at, close + 1);
			}
			fail("no " + group + "{...} group in the snapshot line for phase=" + phase
				+ "; output was:\n" + output);
			return null;
		}

		/** 分组里的某个计数（{@code total{...}} / {@code valueTypes{...}} / {@code channels{...}} 通用）。 */
		String groupValue(String phase, String group, String key) {
			String text = groupText(phase, group);
			for (String pair : text.substring(text.indexOf('{') + 1, text.length() - 1).split(" ")) {
				int eq = pair.indexOf('=');
				if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
					return pair.substring(eq + 1).trim();
				}
			}
			fail("no " + key + "=... inside " + text + " for phase=" + phase + "; output was:\n" + output);
			return null;
		}

		/** 分组内所有计数之和（用于确认没有实例掉出统计；只适用于扁平的计数组）。 */
		String groupSum(String phase, String group) {
			String text = groupText(phase, group);
			int sum = 0;
			for (String pair : text.substring(text.indexOf('{') + 1, text.length() - 1).split(" ")) {
				int eq = pair.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				try {
					sum += Integer.parseInt(pair.substring(eq + 1).trim());
				} catch (NumberFormatException ignored) {
					// 非计数项（如 firstConflict{...}）不计入
				}
			}
			return String.valueOf(sum);
		}

		/**
		 * 取同时满足若干 {@code key=value} 条件的 {@code RESULT <kind>} 行的某个字段。
		 *
		 * <p>用于同一 kind 出现多行、只靠 {@code phase} 分不开的输出（例如分桶行
		 * {@code RESULT bucket phase=… field=… type=… count=…}）。条件按 {@code key, value, key, value}
		 * 成对传入；全部命中才取该行。没有命中返回 null。</p>
		 */
		String resultMatching(String kind, String field, String... conditions) {
			String prefix = "RESULT " + kind;
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.startsWith(prefix)) {
					continue;
				}
				Map<String, String> fields = parse(trimmed.substring(prefix.length()).trim());
				boolean matched = true;
				for (int i = 0; i + 1 < conditions.length; i += 2) {
					if (!conditions[i + 1].equals(fields.get(conditions[i]))) {
						matched = false;
						break;
					}
				}
				if (matched && fields.containsKey(field)) {
					return fields.get(field);
				}
			}
			return null;
		}

		/** 取 {@code RESULT <kind> ... <field>=<value>} 的原始字符串。 */		String result(String kind, String field, String phase) {
			String value = resultRaw(kind, field, phase);
			if (value == null) {
				fail("missing RESULT " + kind + " " + field + "=" + field + " for phase=" + phase
					+ "; output was:\n" + output);
			}
			return value;
		}

		/**
		 * 解析 {@code RESULT <kind> [phase=<phase>] key=value ...}：返回 {@code field} 的原始值。
		 *
		 * @param phase 为 null 时不按 phase 过滤
		 */
		String resultRaw(String kind, String field, String phase) {
			String prefix = "RESULT " + kind;
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.startsWith(prefix)) {
					continue;
				}
				Map<String, String> fields = parse(trimmed.substring(prefix.length()).trim());
				if (phase != null && !phase.equals(fields.get("phase"))) {
					continue;
				}
				if (fields.containsKey(field)) {
					return fields.get(field);
				}
			}
			return null;
		}
	}

	/** 把 {@code a=1 b=2 c{x=1 ...}} 解析成扁平表（括号内的 key 用右括号前缀区分，见 {@link #diffField}）。 */
	private static Map<String, String> parse(String text) {
		Map<String, String> fields = new LinkedHashMap<String, String>();
		int i = 0;
		while (i < text.length()) {
			int eq = text.indexOf('=', i);
			if (eq < 0) {
				break;
			}
			String key = text.substring(i, eq).trim();
			int space = key.lastIndexOf(' ');
			if (space >= 0) {
				key = key.substring(space + 1);
			}
			if (key.endsWith("}")) {
				key = key.substring(0, key.length() - 1);
			}
			int end = eq + 1;
			int depth = 0;
			while (end < text.length()) {
				char c = text.charAt(end);
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
				} else if (depth == 0 && c == ' ') {
					break;
				}
				end++;
			}
			String value = text.substring(eq + 1, end).trim();
			if (value.startsWith("{")) {
				value = value.substring(1);
			}
			if (value.endsWith("}")) {
				value = value.substring(0, value.length() - 1);
			}
			fields.put(key, value);
			i = end + 1;
		}
		return fields;
	}

	/** 从 {@code HEAPWATCH-DIFF} 行里取 {@code total{...}} / {@code null{...}} 分组内的某个计数。 */
	private static String diffField(String diffLine, String field) {
		int groupAt = diffLine.indexOf("total{");
		if (groupAt < 0) {
			fail("grouping line has no total{...} group: " + diffLine);
		}
		int open = diffLine.indexOf('{', groupAt);
		int close = diffLine.indexOf('}', open);
		while (open >= 0 && close > open) {
			for (String pair : diffLine.substring(open + 1, close).split(" ")) {
				int eq = pair.indexOf('=');
				if (eq > 0 && pair.substring(0, eq).equals(field)) {
					return pair.substring(eq + 1);
				}
			}
			open = diffLine.indexOf('{', close);
			close = open < 0 ? -1 : diffLine.indexOf('}', open);
		}
		fail("grouping line has no " + field + "=... entry: " + diffLine);
		return null;
	}
}

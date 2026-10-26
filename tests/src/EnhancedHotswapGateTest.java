import remold.RemoldAgent;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增强重定义能力探测与「结构变化类摘批」判决的回归断言。
 *
 * <p>背景：标准 HotSpot 不接受 schema 变化，且 {@code redefineClasses} 批量提交是全有或全无。
 * 早期实现有两个缺陷：能力探测按 {@code getInputArguments()} 文本判断（被
 * {@code -XX:+IgnoreUnrecognizedVMOptions} 吞掉的标准 JVM 会被误判为支持），
 * 以及结构变化判断写成了 {@code !structureChanged()}（真结构变化静默放行、无变化反而误报）。</p>
 *
 * <p>本类不跳过任何用例（{@code tests/build.gradle} 的 {@code verifyTestCount} 要求
 * {@code skipped == 0}），在标准 JVM 与 JBR 上都有实质断言。</p>
 */
class EnhancedHotswapGateTest {

	@Test
	void structuralChangeWithoutEnhancedCapabilityIsSkipped() {
		assertTrue(RemoldAgent.mustSkipForMissingEnhancedRedefinition(true, false),
			"a schema change on a JVM without enhanced redefinition must leave the batch");
	}

	@Test
	void structuralChangeWithEnhancedCapabilityProceeds() {
		assertFalse(RemoldAgent.mustSkipForMissingEnhancedRedefinition(true, true),
			"JBR/DCEVM handles schema changes natively; the class must stay in the batch");
	}

	@Test
	void methodBodyOnlyChangeIsNeverSkipped() {
		assertFalse(RemoldAgent.mustSkipForMissingEnhancedRedefinition(false, false),
			"method-body-only changes are legal on every HotSpot; the old inverted condition"
			+ " let real schema changes through and flagged harmless ones instead");
	}

	@Test
	void standardHotspotVendorIsNotAnEnhancedRuntime() {
		assertFalse(RemoldAgent.looksLikeEnhancedRuntime(
				"Oracle Corporation OpenJDK 64-Bit Server VM 21.0.2+13-58"),
			"Oracle/Adoptium/Zulu builds cannot change the schema; vendor identity must reject them");
		assertFalse(RemoldAgent.looksLikeEnhancedRuntime(
			"Eclipse Adoptium OpenJDK 64-Bit Server VM 25.0.1+8-27"));
		assertFalse(RemoldAgent.looksLikeEnhancedRuntime(null));
	}

	@Test
	void dcevmFamilyVendorsAreEnhancedRuntimes() {
		assertTrue(RemoldAgent.looksLikeEnhancedRuntime(
			"JetBrains s.r.o. OpenJDK 64-Bit Server VM 21.0.9+1-b1163.86"));
		assertTrue(RemoldAgent.looksLikeEnhancedRuntime("TravaJDK 11 DCEVM"));
	}

	@Test
	void probeMatchesRealVmOptionState() {
		Boolean real = realVmOptionValue();
		boolean probed = RemoldAgent.isEnhancedHotswapEnabled();
		if (real == null) {
			assertFalse(probed,
				"VM option AllowEnhancedClassRedefinition does not exist on this JVM;"
				+ " capability must be reported as false even if the command line mentions it");
		} else {
			assertEquals(real, probed,
				"capability must follow the actual VM option value, not the argument text");
		}
	}

	/**
	 * 标准 JVM 上，{@code FORCE_STATIC_LAMBDA} 必须被强制关闭。
	 *
	 * <p>它把实例 lambda 改写成「静态 + 显式 this 首参数」，方法描述符随之改变 —— 这就是 schema 变化。
	 * 放行等于每一轮都主动制造一次 {@code UnsupportedOperationException}。</p>
	 *
	 * <p>同一条用例同时锁住<b>不降级</b>的方向：{@code INIT_FIX} 必须原样保留。它不会失败，只是
	 * 没机会执行（新增字段已在摘批层被整类拒绝）；强制关闭换不来收益，却会让所有直接调用
	 * {@code InitFix.transform} 的路径静默空转。</p>
	 */
	@Test
	void nonEnhancedRuntimeDegradesForceStaticLambdaOnly() {
		boolean initFix = RemoldAgent.INIT_FIX;
		boolean forceStaticLambda = RemoldAgent.FORCE_STATIC_LAMBDA;
		try {
			RemoldAgent.INIT_FIX = true;
			RemoldAgent.FORCE_STATIC_LAMBDA = true;

			java.util.List<String> degraded = RemoldAgent.degradeForMissingEnhancedRedefinition(false);

			assertEquals(java.util.Collections.singletonList("remold.agent.force_static_lambda"), degraded,
				"only the switch that manufactures a schema change may be degraded");
			assertFalse(RemoldAgent.FORCE_STATIC_LAMBDA,
				"rewriting lambda descriptors is a schema change this JVM cannot apply");
			assertTrue(RemoldAgent.INIT_FIX,
				"InitFix never fails on this JVM (added fields are rejected earlier); forcing it off"
				+ " would silently break direct InitFix callers such as InitFixOracle");
			for (String key : degraded) {
				assertFalse(RemoldAgent.degradationReason(key).isEmpty(),
					"every degraded switch needs a reason for the log: " + key);
			}
		} finally {
			RemoldAgent.INIT_FIX = initFix;
			RemoldAgent.FORCE_STATIC_LAMBDA = forceStaticLambda;
		}
	}

	@Test
	void enhancedRuntimeKeepsSchemaDependentSwitchesUntouched() {
		boolean initFix = RemoldAgent.INIT_FIX;
		boolean forceStaticLambda = RemoldAgent.FORCE_STATIC_LAMBDA;
		try {
			RemoldAgent.INIT_FIX = true;
			RemoldAgent.FORCE_STATIC_LAMBDA = true;

			assertTrue(RemoldAgent.degradeForMissingEnhancedRedefinition(true).isEmpty(),
				"a DCEVM/JBR runtime must not lose the user's explicit configuration");
			assertTrue(RemoldAgent.INIT_FIX);
			assertTrue(RemoldAgent.FORCE_STATIC_LAMBDA);
		} finally {
			RemoldAgent.INIT_FIX = initFix;
			RemoldAgent.FORCE_STATIC_LAMBDA = forceStaticLambda;
		}
	}

	@Test
	void degradationIsIdempotent() {
		boolean initFix = RemoldAgent.INIT_FIX;
		boolean forceStaticLambda = RemoldAgent.FORCE_STATIC_LAMBDA;
		try {
			RemoldAgent.INIT_FIX = true;
			RemoldAgent.FORCE_STATIC_LAMBDA = true;

			assertEquals(1, RemoldAgent.degradeForMissingEnhancedRedefinition(false).size());
			assertTrue(RemoldAgent.degradeForMissingEnhancedRedefinition(false).isEmpty(),
				"a second pass must not re-report switches that are already off");
		} finally {
			RemoldAgent.INIT_FIX = initFix;
			RemoldAgent.FORCE_STATIC_LAMBDA = forceStaticLambda;
		}
	}

	/** 该 VM option 的真实值；option 不存在或 MXBean 不可用时返回 {@code null}。 */
	private static Boolean realVmOptionValue() {
		try {
			Object v = ManagementFactory.getPlatformMBeanServer().invoke(
				new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
				"getVMOption",
				new Object[]{"AllowEnhancedClassRedefinition"},
				new String[]{"java.lang.String"});
			if (v instanceof CompositeData cd) {
				return Boolean.parseBoolean(String.valueOf(cd.get("value")));
			}
			return null;
		} catch (Throwable absent) {
			return null;
		}
	}
}

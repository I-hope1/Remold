import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.ref.InitFix.FieldStatus;
import remold.ref.InitFix.PatchReport;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证字段类型变更（Field Type Changed）在 InitFix 与动态重织入路径下的端到端放行与切片提取。
 */
public class FieldTypeChangeInitFixTest {

	private static final String CLASS_NAME = "test.retyped.WandItem";

	private static final String V1_SRC =
		"package test.retyped;\n"
		+ "public class WandItem {\n"
		+ "    public int fireballPower = 21;\n"
		+ "}\n";

	private static final String V2_SRC =
		"package test.retyped;\n"
		+ "import remold.annotation.RemoldReinit;\n"
		+ "public class WandItem {\n"
		+ "    @RemoldReinit\n"
		+ "    public String fireballPower = \"MAX_POWER\";\n"
		+ "}\n";

	@Test
	public void testFieldTypeChangeWithRemoldReinitInitFixAccepted() throws Exception {
		Map<String, byte[]> v1Classes = LayoutGateAssert.compile(Map.of(CLASS_NAME, V1_SRC));
		Map<String, byte[]> v2Classes = LayoutGateAssert.compile(Map.of(CLASS_NAME, V2_SRC));

		byte[] v1 = v1Classes.get(CLASS_NAME);
		byte[] v2 = v2Classes.get(CLASS_NAME);
		assertNotNull(v1, "V1 编译产物缺失");
		assertNotNull(v2, "V2 编译产物缺失");

		// 加载宿主类（此时为 V1 结构）
		ClassLoader cl = new ClassLoader(FieldTypeChangeInitFixTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (CLASS_NAME.equals(name)) {
					return defineClass(name, v1, 0, v1.length);
				}
				return super.findClass(name);
			}
		};
		Class<?> host = cl.loadClass(CLASS_NAME);

		// 模拟生产环境：bytecodeCache 此时仍持有旧字节码（未完成 redefineClasses）
		RemoldAgent.bytecodeCache.put(CLASS_NAME, v1);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertTrue(diff.typeChangedFields.contains("fireballPower"), "必须检测到 fireballPower 改描述符");
		assertTrue(diff.reinitFields.contains("fireballPower"), "必须识别到 @RemoldReinit 注解");

		// 触发 InitFix 分析
		InitFix.transform(host, v2, diff);

		PatchReport report = InitFix.getLastReport(host);
		assertNotNull(report, "PatchReport 不得为空");
		assertTrue(report.patchGenerated(), "必须成功生成补丁");

		InitFix.FieldDecision decision = report.instanceFields().get("fireballPower");
		assertNotNull(decision, "fireballPower 必须包含在决策表中");
		assertEquals(FieldStatus.ACCEPTED, decision.status(),
			"标有 @RemoldReinit 的类型变更字段在旧字节码缓存下必须被成功提取 (ACCEPTED)，不得报 cannot determine protected status");
	}
}

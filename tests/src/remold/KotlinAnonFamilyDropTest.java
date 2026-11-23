package remold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dropHostGroup} 的原子单元必须覆盖 Kotlin 的宿主名后缀族。
 *
 * <p>Kotlin 把匿名类与 SAM 闭包命名为 {@code Host$方法名$1$1}、{@code Host$方法名$1$1$1}：中间夹着
 * 方法名段，因此 {@code AnonClassAligner.isAnonymousClassName}（只认纯数字链）判不出来。拒绝通道若只
 * 移出宿主与 JVM 形态的匿名类，这一族就会按原名继续重定义 —— 宿主的新字节引用的是重编号后的族成员，
 * 结果要么编号位移篡夺槽位，要么链接到不存在的类。</p>
 *
 * <p>本测试锁两件事：族成员必须被移出；具名内部类（尾部不是数字链）必须**不**被误移出 —— 后者是
 * 过度拒绝的负向对照。</p>
 */
class KotlinAnonFamilyDropTest {

	private static Map<String, byte[]> batch(String... names) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (String n : names) out.put(n, new byte[]{0});
		return out;
	}

	@Test
	void kotlinHostSuffixFamilyIsDroppedWithItsHost() {
		Map<String, byte[]> bytes = batch(
			"pkg.Host",
			"pkg.Host$metricTable$1$1",       // Kotlin anonymous object
			"pkg.Host$metricTable$1$1$1",     // its SAM closure (class mode)
			"pkg.Host$1",                     // JVM-shaped anonymous class
			"pkg.Host$Inner",                 // named inner class: NOT part of the family
			"pkg.Other");                     // unrelated class
		Map<String, Path> paths = new LinkedHashMap<>();
		for (String n : bytes.keySet()) paths.put(n, Path.of("/tmp/" + n.replace('.', '/') + ".class"));

		List<String> dropped = new ArrayList<>(RemoldAgent.dropHostGroup(bytes, paths, "pkg.Host"));

		assertTrue(dropped.contains("pkg.Host"), "host must be moved out: " + dropped);
		assertTrue(dropped.contains("pkg.Host$metricTable$1$1"),
			"Kotlin host-suffix anonymous object must be moved out: " + dropped);
		assertTrue(dropped.contains("pkg.Host$metricTable$1$1$1"),
			"Kotlin SAM closure under the anonymous object must be moved out: " + dropped);
		assertTrue(dropped.contains("pkg.Host$1"), "JVM-shaped anonymous class must stay atomic: " + dropped);

		assertTrue(bytes.containsKey("pkg.Host$Inner"),
			"named inner class must survive the drop (negative control): " + bytes.keySet());
		assertTrue(bytes.containsKey("pkg.Other"), "unrelated class must survive: " + bytes.keySet());
		assertFalse(bytes.containsKey("pkg.Host"), "host must be gone from the batch");
	}
}

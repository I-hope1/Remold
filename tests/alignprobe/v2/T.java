import java.util.*;
import java.util.function.*;

/** 对齐基线回归夹具 v2：删掉 A2（A3 的 javac 编号随之前移）。 */
public class T {
	public void build(Map<String, Supplier<String>> m) {
		m.put("A1", () -> "A1:v2");
		m.put("A3", () -> "A3:v2");
	}
}

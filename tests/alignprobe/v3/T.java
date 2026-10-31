import java.util.*;
import java.util.function.*;

/** 对齐基线回归夹具 v3：把 A2 加回来（源码形状回到 v1，但已隔了一轮删除）。 */
public class T {
	public void build(Map<String, Supplier<String>> m) {
		m.put("A1", () -> "A1:v3");
		m.put("A2", () -> "A2:v3");
		m.put("A3", () -> "A3:v3");
	}
}

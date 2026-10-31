import java.util.*;
import java.util.function.*;

/** 对齐基线回归夹具 v1：三个无捕获 lambda。 */
public class T {
	public void build(Map<String, Supplier<String>> m) {
		m.put("A1", () -> "A1:v1");
		m.put("A2", () -> "A2:v1");
		m.put("A3", () -> "A3:v1");
	}
}

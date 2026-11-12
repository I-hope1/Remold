package testPassBMove2;

import java.util.Map;
import java.util.function.Supplier;

public class PassBMove2Case {
    public void build(Map<String, Supplier<String>> m) {
        m.put("k", () -> {
            m.put("k2", () -> {
                int[] a = new int[6];
                return "k:" + a.length;
            });
            return "outer";
        });
    }
}

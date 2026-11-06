import remold.RemoldAgent;
import remold.ref.InitializerDrift;

import java.util.List;
import java.util.Map;

/** Prints the L0 output for the common named-class edit int x = 1 -> 2. */
public class InitDriftNoiseProbe {
	public static void main(String[] args) throws Exception {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("noise.Host", """
			package noise;
			public class Host { public int x = 1; }
			"""));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("noise.Host", """
			package noise;
			public class Host { public int x = 2; }
			"""));
		RemoldAgent.bytecodeCache.put("noise.Host", v1.get("noise.Host"));
		RemoldAgent.Logger old = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) {}
			public void info(String msg) {}
			public void warn(String msg) { System.out.println("[WARN] " + msg); }
			public void error(String msg) {}
			public void error(String msg, Throwable t) {}
		};
		try {
			InitializerDrift.report(Map.of("noise.Host", v2.get("noise.Host")));
		} finally {
			RemoldAgent.logger = old;
		}
	}
}

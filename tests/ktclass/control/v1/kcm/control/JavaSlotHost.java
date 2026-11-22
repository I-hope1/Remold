package kcm.control;

import java.util.function.Supplier;

/**
 * Java anonymous-class control group, v1.
 *
 * Same host-suffix naming and the same "only the labels change" edit as the Kotlin fixtures, but
 * with javac output: `JavaSlotHost$1` / `$2` resolve to a real host class, and the two anonymous
 * classes differ structurally (beta carries a field), so the alignment stage has unique evidence.
 *
 * The control must therefore apply v2 on a plain HotSpot JVM — that is what proves the K1/K3
 * readings on the Kotlin fixtures are about the Kotlin shape and not about a driver that never
 * redefines anything.
 */
public class JavaSlotHost {

	public final Supplier<String> alpha;
	public final Supplier<String> beta;

	public JavaSlotHost() {
		this.alpha = new Supplier<String>() {
			public String get() { return "j1-alpha"; }
		};
		this.beta = new Supplier<String>() {
			private int hits;

			public String get() {
				hits = hits + 1;
				return "j1-beta";
			}
		};
	}

	public String alphaLabel() { return alpha.get(); }

	public String betaLabel() { return beta.get(); }

	public String metricTable() { return "t1:" + alpha.get() + "|" + beta.get(); }
}

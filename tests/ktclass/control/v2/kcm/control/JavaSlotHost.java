package kcm.control;

import java.util.function.Supplier;

/**
 * Java anonymous-class control group, v2: identical class set (no added anonymous class, so the
 * host's {@code NestMembers} attribute does not change and a plain HotSpot redefine is legal),
 * identical structure, only the labels change.
 */
public class JavaSlotHost {

	public final Supplier<String> alpha;
	public final Supplier<String> beta;

	public JavaSlotHost() {
		this.alpha = new Supplier<String>() {
			public String get() { return "j2-alpha"; }
		};
		this.beta = new Supplier<String>() {
			private int hits;

			public String get() {
				hits = hits + 1;
				return "j2-beta";
			}
		};
	}

	public String alphaLabel() { return alpha.get(); }

	public String betaLabel() { return beta.get(); }

	public String metricTable() { return "t2:" + alpha.get() + "|" + beta.get(); }
}

package remold;

import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil.ClassDiff;
import remold.ref.InitFix;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The classifier behind {@code [INITFIX-REFUSE]}: which newly added fields make the whole class
 * un-hot-swappable.
 *
 * <p>The distinction it must keep is the whole point of the gate: an initializer that <b>exists in source
 * but cannot be sliced</b> leaves a live field at its type default (a Kotlin non-null {@code val} reads
 * {@code null}), so the class must be refused; an initializer that comes from a constructor parameter, or
 * no initializer at all, is ordinary and must stay hot-swappable.</p>
 *
 * <p>Scope note: this covers the classifier only. The wiring (class dropped from the batch, live instance
 * untouched, no false success line) is proven end-to-end on JBR by
 * {@code :tests:initFixUnexpressibleProbe}.</p>
 */
class InitFixUnexpressibleInitTest {

	private static final String UNEXPRESSIBLE =
		InitFix.UNEXPRESSIBLE_INIT_PREFIX + " in <init>()V: contains branch";

	@Test
	void unexpressibleInitializerOnAddedFieldIsReported() {
		ClassDiff diff = diffWithAdded("label");
		InitFix.PatchReport report = reportWith(Map.of(
			"label", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)));

		Map<String, String> refused = InitFix.classifyUnexpressibleNewFieldInits(report, diff);

		assertEquals(1, refused.size(), "the unsliceable initializer must be reported: " + refused);
		assertTrue(refused.containsKey("label"), refused.toString());
		assertTrue(refused.get("label").startsWith(InitFix.UNEXPRESSIBLE_INIT_PREFIX),
			"the reason must stay in the stable unexpressible family: " + refused.get("label"));
	}

	@Test
	void otherRejectionsAreNotTreatedAsUnexpressible() {
		// A value that comes from a constructor parameter has no computable source at all; that is the
		// documented boundary, not a silent-null landmine, so it must NOT refuse the class.
		ClassDiff diff = diffWithAdded("fromParam");
		InitFix.PatchReport report = reportWith(Map.of(
			"fromParam", InitFix.FieldDecision.rejected("self-assignment from its own constructor parameter")));

		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty(),
			"param-sourced initializers keep the existing per-field behaviour");
	}

	@Test
	void preExistingFieldsAreIgnored() {
		// The same rejection on a field that is NOT newly added must not refuse the class: InitFix was
		// asked about it for another reason (reinit/ledger), and the baseline already has it.
		ClassDiff diff = diffWithAdded("other");
		InitFix.PatchReport report = reportWith(Map.of(
			"legacy", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)));

		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty(),
			"only added fields can make the class un-hot-swappable");
	}

	@Test
	void staticAddedFieldsAreCoveredToo() {
		ClassDiff diff = new ClassDiff();
		diff.addedStaticFields.add("CONST");
		InitFix.PatchReport report = new InitFix.PatchReport(
			Map.of(), Map.of("CONST", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)), false, Map.of());

		Map<String, String> refused = InitFix.classifyUnexpressibleNewFieldInits(report, diff);
		assertTrue(refused.containsKey("CONST"), "static added fields follow the same rule: " + refused);
	}

	@Test
	void acceptedAddedFieldIsNotReported() {
		ClassDiff diff = diffWithAdded("added");
		InitFix.PatchReport report = reportWith(Map.of("added", InitFix.FieldDecision.ACCEPTED));
		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty());
	}

	private static ClassDiff diffWithAdded(String field) {
		ClassDiff diff = new ClassDiff();
		diff.addedInstanceFields.add(field);
		return diff;
	}

	private static InitFix.PatchReport reportWith(Map<String, InitFix.FieldDecision> instanceFields) {
		return new InitFix.PatchReport(instanceFields, Map.of(), true, Map.of());
	}
}

import org.junit.jupiter.api.Test;
import remold.annotation.RemoldReinit;
import remold.ref.InitFix;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain-JVM contract tests for {@code @RemoldReinit(scope = ...)}
 * ({@code docs/initfix/03-runtime-driver.md} §4).
 *
 * <p>These tests cover only the annotation surface and the pure instance-selection helper.
 * The end-to-end behaviour across a real structural redefine (including the JBR shadow-Class
 * identity case) is covered by {@code :tests:scopeReinitProbe}.</p>
 */
public class ScopeReinitTest {

	static class Parent {
		int value;
	}

	static class Child extends Parent {
	}

	static class GrandChild extends Child {
	}

	static class Unrelated {
		int value;
	}

	static class Holder {
		@RemoldReinit
		int plain;

		@RemoldReinit(scope = RemoldReinit.Scope.SELF)
		int selfOnly;

		@RemoldReinit(scope = RemoldReinit.Scope.INCLUDE_SUBCLASSES, mode = RemoldReinit.Mode.CONDITIONAL)
		int explicitInclude;
	}

	// ---------------------------------------------------------------- annotation surface

	@Test
	void annotationScopeElementDefaultsToIncludeSubclasses() throws Exception {
		Method scope = RemoldReinit.class.getDeclaredMethod("scope");
		assertEquals(RemoldReinit.Scope.INCLUDE_SUBCLASSES, scope.getDefaultValue(),
			"scope() default must keep the existing 'declaring class + subclasses' behaviour");
	}

	@Test
	void annotationModeElementDefaultStaysOverwrite() throws Exception {
		Method mode = RemoldReinit.class.getDeclaredMethod("mode");
		assertEquals(RemoldReinit.Mode.OVERWRITE, mode.getDefaultValue(),
			"adding scope() must not change the mode() default");
	}

	@Test
	void scopeEnumContainsExactlySelfAndIncludeSubclasses() {
		List<String> names = Arrays.stream(RemoldReinit.Scope.values())
			.map(Enum::name)
			.toList();
		assertEquals(List.of("SELF", "INCLUDE_SUBCLASSES"), names);
	}

	@Test
	void annotationScopeValuesAreReadable() throws Exception {
		RemoldReinit plain = Holder.class.getDeclaredField("plain").getAnnotation(RemoldReinit.class);
		assertEquals(RemoldReinit.Scope.INCLUDE_SUBCLASSES, plain.scope(),
			"unset scope() must read back as the default");

		RemoldReinit self = Holder.class.getDeclaredField("selfOnly").getAnnotation(RemoldReinit.class);
		assertEquals(RemoldReinit.Scope.SELF, self.scope());

		RemoldReinit include = Holder.class.getDeclaredField("explicitInclude").getAnnotation(RemoldReinit.class);
		assertEquals(RemoldReinit.Scope.INCLUDE_SUBCLASSES, include.scope());
		assertEquals(RemoldReinit.Mode.CONDITIONAL, include.mode());
	}

	// ---------------------------------------------------------------- pure decision helper

	@Test
	void selfScopeMatchesOnlyExactDeclaringClass() {
		Parent parent = new Parent();
		Child child = new Child();
		GrandChild grandChild = new GrandChild();

		assertTrue(InitFix.scopeMatches(Parent.class, parent, RemoldReinit.Scope.SELF),
			"SELF must match an instance whose runtime class is exactly the declaring class");
		assertFalse(InitFix.scopeMatches(Parent.class, child, RemoldReinit.Scope.SELF),
			"SELF must exclude direct subclass instances");
		assertFalse(InitFix.scopeMatches(Parent.class, grandChild, RemoldReinit.Scope.SELF),
			"SELF must exclude transitive subclass instances");
	}

	@Test
	void includeSubclassesScopeMatchesDeclaringClassAndSubclasses() {
		Parent parent = new Parent();
		Child child = new Child();
		GrandChild grandChild = new GrandChild();

		assertTrue(InitFix.scopeMatches(Parent.class, parent, RemoldReinit.Scope.INCLUDE_SUBCLASSES));
		assertTrue(InitFix.scopeMatches(Parent.class, child, RemoldReinit.Scope.INCLUDE_SUBCLASSES));
		assertTrue(InitFix.scopeMatches(Parent.class, grandChild, RemoldReinit.Scope.INCLUDE_SUBCLASSES));
	}

	@Test
	void includeSubclassesIsAlsoTheNullScopeFallback() {
		Child child = new Child();
		assertTrue(InitFix.scopeMatches(Parent.class, child, null),
			"null scope must fall back to the INCLUDE_SUBCLASSES default");
	}

	@Test
	void unrelatedInstanceIsExcludedByBothScopes() {
		Unrelated unrelated = new Unrelated();
		assertFalse(InitFix.scopeMatches(Parent.class, unrelated, RemoldReinit.Scope.SELF));
		assertFalse(InitFix.scopeMatches(Parent.class, unrelated, RemoldReinit.Scope.INCLUDE_SUBCLASSES));
	}

	@Test
	void nullInputsAreFailClosed() {
		assertFalse(InitFix.scopeMatches(null, new Parent(), RemoldReinit.Scope.SELF));
		assertFalse(InitFix.scopeMatches(Parent.class, null, RemoldReinit.Scope.SELF));
		assertFalse(InitFix.scopeMatches(null, null, RemoldReinit.Scope.INCLUDE_SUBCLASSES));
	}
}

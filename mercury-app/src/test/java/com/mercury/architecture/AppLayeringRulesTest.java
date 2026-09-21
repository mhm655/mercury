package com.mercury.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The no-orphaned-API rule, extended to the application module.
 *
 * <h2>Why this test exists at all</h2>
 * The engine's version of this rule lives in {@code mercury-engine} and imports
 * {@code com.mercury} from that module's own test classpath - which, by construction, cannot
 * see {@code mercury-app}. The dependency runs one way: the app knows about the engine and
 * never the reverse.
 *
 * <p>So the composition root was the one place in the project with no dead-code guard, and it
 * quietly accumulated one: {@code DemoScenario.clock()} built a {@code SimulationClock} that
 * nothing ever called. Small, harmless, and exactly the thing the engine's rule was written to
 * make impossible - missed because the rule could not reach here.
 *
 * <p>The lesson is worth more than the method that was deleted. A check that covers most of a
 * codebase is believed to cover all of it, and the part it cannot see is the part that drifts.
 *
 * <h2>Scoped to the app</h2>
 * Only {@code com.mercury.app} classes are examined; the engine has its own rule and running
 * both over the same classes would double the failure message without doubling the coverage.
 */
class AppLayeringRulesTest {

    private static final Set<String> ALWAYS_EXEMPT =
            Set.of("equals", "hashCode", "toString", "main", "values", "valueOf", "compareTo");

    @Test
    @DisplayName("every public method in the app module is called from somewhere")
    void noOrphanedApiInTheAppModule() {
        // Tests included on purpose, for the same reason as in the engine: a method exercised
        // only by tests is used, and demanding a production caller would push toward deleting
        // things that are genuinely part of the API.
        JavaClasses appClasses = new ClassFileImporter().importPackages("com.mercury.app");

        List<String> orphans = new ArrayList<>();
        for (JavaClass clazz : appClasses) {
            if (clazz.getSimpleName().endsWith("Test") || clazz.isAnonymousClass()) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (isExempt(clazz, method)) {
                    continue;
                }
                if (method.getAccessesToSelf().isEmpty()) {
                    orphans.add(clazz.getSimpleName() + "." + method.getName() + "()");
                }
            }
        }

        assertThat(orphans)
                .as("public methods in mercury-app with no caller anywhere. The composition "
                        + "root is allowed to be the only place that knows what exists - it is "
                        + "not allowed to build things nobody asks for")
                .isEmpty();
    }

    /**
     * The other half of the engine's allowlist.
     *
     * <p>{@code NoOrphanedApiTest} in {@code mercury-engine} cannot see this module, so engine
     * API called only from the composition root looks dead to it. It carries a short list of
     * such methods - and a list nothing checks is how a dead method acquires a permanent
     * excuse. This is the check: every name the engine exempts on the grounds that the app
     * uses it must actually be used here.
     *
     * <p>Kept as a literal rather than imported from the engine's test class, because test
     * classes are not published between modules. Two short lists that must agree, with a
     * failing build when they do not, beats one list nobody verifies.
     */
    private static final Set<String> ENGINE_EXEMPTS_BECAUSE_THIS_MODULE_CALLS_IT =
            Set.of("PortfolioLedger.costBasisMethod");

    @Test
    @DisplayName("engine API exempted as app-only really is called from the app")
    void engineApiThisModuleClaimsToUse() {
        JavaClasses appClasses = new ClassFileImporter().importPackages("com.mercury.app");

        List<String> unused = new ArrayList<>();
        for (String exempt : ENGINE_EXEMPTS_BECAUSE_THIS_MODULE_CALLS_IT) {
            String methodName = exempt.substring(exempt.indexOf('.') + 1);
            String owner = exempt.substring(0, exempt.indexOf('.'));
            boolean called = appClasses.stream()
                    .flatMap(clazz -> clazz.getMethodCallsFromSelf().stream())
                    .anyMatch(call -> call.getName().equals(methodName)
                            && call.getTargetOwner().getSimpleName().equals(owner));
            if (!called) {
                unused.add(exempt);
            }
        }

        assertThat(unused)
                .as("mercury-engine's NoOrphanedApiTest exempts these from its dead-API rule on "
                        + "the grounds that this module calls them, and this module does not. "
                        + "Either call them or delete both the method and its exemption - an "
                        + "allowlist nothing verifies is how dead code gets a permanent excuse")
                .isEmpty();
    }

    private static boolean isExempt(JavaClass clazz, JavaMethod method) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
            return true;
        }
        if (ALWAYS_EXEMPT.contains(method.getName())) {
            return true;
        }
        if (isRecordAccessor(clazz, method)) {
            return true;
        }
        if (method.getModifiers().contains(JavaModifier.ABSTRACT) || clazz.isInterface()) {
            return true;
        }
        return overridesSupertypeMethod(clazz, method);
    }

    /**
     * A method overriding an interface or superclass declaration - invoked polymorphically
     * through that supertype, which is where the call site is recorded, not against this
     * class directly.
     *
     * <h2>Missing until M16</h2>
     * The engine's copy of this rule ({@code NoOrphanedApiTest}) has carried this exemption
     * from the start; this copy did not, because nothing in {@code mercury-app} had
     * implemented a functional interface across an event-bus subscription until
     * {@code Blotter implements Consumer<TradeExecuted>} - the same shape
     * {@code LedgerKeeper} already uses in the engine. {@code SynchronousEventBus.publish}
     * calls {@code subscriber.accept(event)} against the {@code Consumer} reference it holds,
     * so the bytecode call site targets {@code Consumer.accept}, not {@code Blotter.accept} -
     * invisible to a rule that only looks for accesses recorded against the concrete class.
     * A gap two copies of the same check can drift into, closed the same way §M15's
     * dead-weight audit closed the last one: by making the two copies agree, not by routing
     * one call site around the rule.
     */
    private static boolean overridesSupertypeMethod(JavaClass clazz, JavaMethod method) {
        List<String> parameters = method.getRawParameterTypes().stream()
                .map(JavaClass::getName).toList();

        List<JavaClass> supertypes = new ArrayList<>(clazz.getAllRawInterfaces());
        supertypes.addAll(clazz.getAllRawSuperclasses());

        return supertypes.stream().anyMatch(supertype -> supertype.getMethods().stream()
                .anyMatch(candidate -> candidate.getName().equals(method.getName())
                        && candidate.getRawParameterTypes().stream()
                                .map(JavaClass::getName).toList().equals(parameters)));
    }

    /**
     * A no-argument method named after one of the record's own components.
     *
     * <p>Narrower than it looks, and deliberately so. The first version of this exempted every
     * no-argument method on a record, which excuses far more than accessors - a planted orphan
     * on {@code RiskFactors} sailed straight through it, and the rule was passing vacuously on
     * the very module it was written to cover. Checking the name against the component list is
     * what makes the exemption mean what it says.
     */
    private static boolean isRecordAccessor(JavaClass clazz, JavaMethod method) {
        if (!clazz.isRecord() || !method.getRawParameterTypes().isEmpty()) {
            return false;
        }
        // Matched against the record's fields rather than a component list, because ArchUnit
        // 1.3 does not expose components - a record component and its backing field share a
        // name, so the two questions have the same answer.
        return clazz.getAllFields().stream()
                .anyMatch(field -> field.getName().equals(method.getName()));
    }
}

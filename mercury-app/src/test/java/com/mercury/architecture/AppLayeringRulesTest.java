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
        return method.getModifiers().contains(JavaModifier.ABSTRACT) || clazz.isInterface();
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

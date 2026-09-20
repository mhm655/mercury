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
 * Fails the build when a public method has no caller anywhere - production code, tests or
 * benchmarks.
 *
 * <h2>Why this rule exists</h2>
 * Three separate audit findings turned out to be the same mistake repeated:
 *
 * <ul>
 *   <li>{@code OrderNode.sequence} - a field written on every insert, never read, whose
 *       javadoc claimed it established time priority. It established nothing.</li>
 *   <li>{@code PayReceive.sign()} - never called, and documented as letting cashflows be
 *       "flipped by multiplication rather than by branching" while both legs branched.</li>
 *   <li>Nine further methods with no caller at all.</li>
 * </ul>
 *
 * The mechanism was consistent: write the javadoc describing the intended design, implement
 * something slightly different, never reconcile the two. In most projects that is cosmetic.
 * Here the documentation <em>is</em> the product - it is what a reviewer judges - so a
 * comment asserting behaviour the code does not have costs more than the dead code does. A
 * reader who catches one stops trusting the rest.
 *
 * <p>Vigilance had already failed three times, so this is mechanical instead. Unused API is
 * also surface a reader must work through for no return, which matters in a codebase whose
 * point is being read.
 *
 * <h2>What counts as used</h2>
 * Any bytecode reference from anywhere in {@code com.mercury}, including tests - a method
 * exercised only by tests is legitimately in use, and demanding a production caller would
 * push toward deleting things that are genuinely part of the API.
 *
 * <p>Deliberately exempt, because absence of a direct call does not imply they are dead:
 * record accessors and constructors, {@code equals}/{@code hashCode}/{@code toString},
 * enum {@code values}/{@code valueOf}, and any method overriding a supertype declaration -
 * those are invoked polymorphically through the supertype, which is where the call site is
 * recorded.
 */
class NoOrphanedApiTest {

    private static final Set<String> ALWAYS_EXEMPT = Set.of(
            "equals", "hashCode", "toString", "main", "values", "valueOf", "compareTo");

    /**
     * Engine API whose only caller lives in {@code mercury-app}.
     *
     * <h2>Why a list and not a smarter rule</h2>
     * This module's test classpath cannot see {@code mercury-app} - the dependency runs one
     * way, which is the property {@code AppLayeringRulesTest} exists to protect. So an engine
     * method used solely by the composition root looks dead from here and is not, and there is
     * no structural way to tell that apart from the real thing inside this module.
     *
     * <p>An allowlist rots if nothing checks it, so something does:
     * {@code AppLayeringRulesTest.engineApiThisModuleClaimsToUse} asserts every entry really is
     * called from {@code mercury-app}. An entry that stops being used fails the build in the
     * other module rather than silently excusing a method that has genuinely died.
     */
    static final Set<String> CALLED_ONLY_FROM_THE_APP_MODULE = Set.of(
            "PortfolioLedger.costBasisMethod");

    @Test
    @DisplayName("every public method is called from somewhere")
    void everyPublicMethodHasACaller() {
        // Tests included on purpose: a method used only by tests is used.
        JavaClasses all = new ClassFileImporter().importPackages("com.mercury");

        List<String> orphans = new ArrayList<>();
        for (JavaClass clazz : all) {
            if (isTestClass(clazz) || clazz.isAnonymousClass()) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (isExempt(clazz, method)) {
                    continue;
                }
                if (method.getAccessesToSelf().isEmpty()) {
                    // Include parameter types: overloads are independently live or dead, and
                    // reporting just the name makes a used overload look like a dead one.
                    orphans.add(clazz.getSimpleName() + "." + method.getName() + "("
                            + String.join(", ", method.getRawParameterTypes().stream()
                                    .map(JavaClass::getSimpleName).toList())
                            + ")");
                }
            }
        }

        assertThat(orphans)
                .as("public methods with no caller anywhere. Either use them, or delete them - "
                        + "an unused method with a javadoc justifying its existence is the "
                        + "specific failure this rule was added to prevent")
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
        if (CALLED_ONLY_FROM_THE_APP_MODULE.contains(
                clazz.getSimpleName() + "." + method.getName())) {
            return true;
        }
        // An abstract declaration is invoked through its implementations, and ArchUnit
        // records the call against whichever type it was made through - often the concrete
        // class. Known limitation: an interface method nothing implements would slip past.
        if (method.getModifiers().contains(JavaModifier.ABSTRACT) || clazz.isInterface()) {
            return true;
        }
        return overridesSupertypeMethod(clazz, method);
    }

    /**
     * A no-arg method named after one of the record's own components.
     *
     * <h2>The record check is the whole point of this method</h2>
     * Without {@code isRecord()} this exempted any no-arg method on any class whose name
     * matched any field - and since the house style is {@code private final T foo} beside
     * {@code public T foo()}, that is every getter in the codebase. The rule meant to exempt
     * 2 record accessors was exempting 61 methods, so roughly a quarter of the no-arg API
     * surface went unchecked, and it was exactly the quarter where dead accessors collect.
     * Three had: {@code PositionLots.lots()} and the two exception {@code key()} accessors.
     *
     * <p>{@code getAllFields} rather than the declared ones is also deliberate now that the
     * record check is in place: a record's components are its declared fields, so inherited
     * ones cannot widen this, and the narrower call would be no safer.
     */
    private static boolean isRecordAccessor(JavaClass clazz, JavaMethod method) {
        if (!clazz.isRecord() || !method.getRawParameterTypes().isEmpty()) {
            return false;
        }
        return clazz.getAllFields().stream()
                .anyMatch(field -> field.getName().equals(method.getName()));
    }

    /**
     * True if any supertype declares the same signature.
     *
     * <p>ArchUnit records a polymorphic call against the type it was made through, so an
     * implementation of an interface method legitimately has no direct accesses of its own.
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

    private static boolean isTestClass(JavaClass clazz) {
        JavaClass outermost = clazz;
        while (outermost.getEnclosingClass().isPresent()) {
            outermost = outermost.getEnclosingClass().get();
        }
        String name = outermost.getSimpleName();
        return name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("Properties");
    }
}

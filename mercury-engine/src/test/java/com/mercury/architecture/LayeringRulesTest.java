package com.mercury.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules, enforced as tests.
 *
 * <p>A README can claim that the engine is framework-independent and that the domain does
 * not depend on infrastructure. Claims rot: the first person in a hurry adds the import
 * and nothing complains. These rules fail the build instead, which is the difference
 * between an architecture and an aspiration.
 *
 * <p>Rules are added here as the layers they describe come into existence, so this class
 * grows with the project rather than asserting things about packages that do not exist yet.
 */
class LayeringRulesTest {

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importClasses() {
        engineClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mercury");
    }

    @Test
    @DisplayName("no production class reads a real clock")
    void nothingReadsTheSystemClock() {
        // The single most important rule in the project. Reproducibility, testability and
        // pure valuation all depend on time entering through SimulationClock and nowhere
        // else - see docs/DESIGN_PROPOSAL.md section A2.4.
        noClasses()
                .that().resideOutsideOfPackage("com.mercury.core.time..")
                .should().callMethod(java.time.LocalDate.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .because("time must enter the engine through SimulationClock, so that a run "
                        + "is reproducible and valuation is a pure function of its inputs")
                .check(engineClasses);
    }

    @Test
    @DisplayName("the engine depends on no framework")
    void engineIsFrameworkFree() {
        noClasses()
                .that().resideInAPackage("com.mercury..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "com.fasterxml.jackson..")
                .because("the engine must be usable from a plain main() with no container, "
                        + "which is what makes the later Spring module a pure addition")
                .check(engineClasses);
    }

    @Test
    @DisplayName("money is never represented as a floating point primitive field")
    void noFloatingPointMoneyFields() {
        noClasses()
                .that().resideInAPackage("com.mercury.core.money..")
                .should().haveFieldsThat().haveRawType(double.class)
                .andShould().haveFieldsThat().haveRawType(float.class)
                .because("ledger amounts are exact decimals; only model outputs are doubles, "
                        + "and those live outside the money package")
                .check(engineClasses);
    }

    @Test
    @DisplayName("there are no package cycles")
    void noPackageCycles() {
        SlicesRuleDefinition.slices()
                .matching("com.mercury.(**)")
                .should().beFreeOfCycles()
                .because("a cycle means two packages cannot be understood, tested or changed "
                        + "independently")
                .check(engineClasses);
    }

    @Test
    @DisplayName("core value types do not print to the console")
    void noConsoleOutputFromTheDomain() {
        noClasses()
                .that().resideInAPackage("com.mercury.core..")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("presentation belongs to mercury-app; the engine communicates through "
                        + "return values, domain events and exceptions")
                .check(engineClasses);
    }
}

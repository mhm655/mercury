package com.mercury.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
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
        // Coverage matters as much as the rule. An earlier version checked only LocalDate,
        // LocalDateTime, Instant and System - leaving ZonedDateTime.now(), LocalTime.now(),
        // new Date() and Calendar.getInstance() as open doors, while the documentation
        // claimed no production class reads a clock. A partially enforced rule is worse than
        // an absent one, because it is believed.
        noClasses()
                .that().resideOutsideOfPackage("com.mercury.core.time..")
                .should().callMethod(java.time.LocalDate.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(java.time.LocalTime.class, "now")
                .orShould().callMethod(java.time.ZonedDateTime.class, "now")
                .orShould().callMethod(java.time.OffsetDateTime.class, "now")
                .orShould().callMethod(java.time.OffsetTime.class, "now")
                .orShould().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.Year.class, "now")
                .orShould().callMethod(java.time.YearMonth.class, "now")
                .orShould().callMethod(java.time.MonthDay.class, "now")
                .orShould().callMethod(java.time.Clock.class, "systemUTC")
                .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .orShould().callMethod(java.util.Calendar.class, "getInstance")
                .orShould().callConstructor(java.util.Date.class)
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
    @DisplayName("ledger value types never hold a floating point field")
    void ledgerTypesAreExact() {
        // Scoped to the three exact-decimal types rather than the whole money package,
        // because BasisPoints also lives there and is deliberately a double: it is a model
        // quantity feeding discount factors and Greeks, not a ledger fact. See ADR 0001.
        noFields()
                .that().areDeclaredInClassesThat().belongToAnyOf(
                        Money.class, Price.class, Quantity.class)
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .because("ledger amounts must be exact; binary floating point cannot represent "
                        + "0.10, so cash arithmetic in double does not reconcile")
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

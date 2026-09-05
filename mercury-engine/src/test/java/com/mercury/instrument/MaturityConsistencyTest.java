package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks {@link Maturing} against {@link CashflowGenerating}.
 *
 * <h2>Why this test exists</h2>
 * A bond maturing on a Saturday used to report its <em>contractual</em> maturity from
 * {@code maturityDate()} while paying on the following Monday. On the Saturday it therefore
 * claimed to have matured while still owing its principal - and any caller filtering out
 * matured positions would have silently dropped a position worth over a million dollars. No
 * exception, no warning, just a wrong number.
 *
 * <p>`Bond` and `Schedule` were each individually correct and individually well tested. They
 * disagreed with each other, and nothing looked at the seam between them. That is the general
 * lesson: unit tests verify components, and integration bugs live in the gaps between
 * components that are each fine on their own.
 *
 * <p>These tests deliberately use dates that fall on weekends, because the defect is
 * invisible on any date that happens to be a business day.
 */
class MaturityConsistencyTest {

    private static final LocalDate ISSUE = LocalDate.of(2024, 5, 15);

    /** Saturday. The date the original defect needed to surface. */
    private static final LocalDate SATURDAY_MATURITY = LocalDate.of(2027, 5, 15);

    private static Bond bondMaturingOnSaturday() {
        return Bond.builder()
                .id("B-SAT").faceValue(Money.of("1000000", Currency.USD)).couponRate("0.05")
                .couponFrequency(Frequency.ANNUAL).calendar(HolidayCalendar.weekendsOnly())
                .issueDate(ISSUE).maturityDate(SATURDAY_MATURITY).build();
    }

    /** Every maturing instrument, including ones whose contractual maturity is a weekend. */
    private static List<FinancialInstrument> maturingInstruments() {
        return List.of(
                bondMaturingOnSaturday(),
                Bond.builder()
                        .id("B-WEEKDAY").faceValue(Money.of("1000000", Currency.USD))
                        .couponRate("0.05").calendar(HolidayCalendar.weekendsOnly())
                        .issueDate(ISSUE).maturityDate(LocalDate.of(2029, 5, 15)).build(),
                FxForward.buy("FWD", CurrencyPair.parse("EUR/USD"), "1000000", "1.08",
                        LocalDate.of(2025, 3, 17)),
                EuropeanOption.call("OPT", com.mercury.core.id.InstrumentId.of("AAPL"),
                        Price.of("200"), LocalDate.of(2025, 1, 17), Currency.USD),
                InterestRateSwap.builder()
                        .id("IRS").notional(Money.of("10000000", Currency.USD))
                        .fixedRate("0.04").index(FloatingRateIndex.usdSofr3M())
                        .calendar(HolidayCalendar.weekendsOnly())
                        .effectiveDate(ISSUE).maturityDate(SATURDAY_MATURITY).build());
    }

    @Nested
    @DisplayName("the invariant that was violated")
    class TheInvariant {

        @Test
        @DisplayName("nothing is still owed on the maturity date")
        void nothingOwedAtMaturity() {
            // The core contract of Maturing: cashflows(maturityDate()) must be empty.
            for (FinancialInstrument instrument : maturingInstruments()) {
                if (instrument instanceof Maturing maturing
                        && instrument instanceof CashflowGenerating generator) {

                    assertThat(generator.cashflows(maturing.maturityDate()))
                            .as("%s still owes money on its stated maturity date %s",
                                    instrument.id(), maturing.maturityDate())
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("hasMaturedAsOf agrees with having no cashflows left")
        void maturedFlagAgreesWithCashflows() {
            for (FinancialInstrument instrument : maturingInstruments()) {
                if (instrument instanceof Maturing maturing
                        && instrument instanceof CashflowGenerating generator) {

                    LocalDate maturity = maturing.maturityDate();
                    // One day before maturity there must still be something outstanding,
                    // otherwise the instrument matured earlier than it claims.
                    assertThat(generator.cashflows(maturity.minusDays(1)))
                            .as("%s reports maturity %s but owes nothing the day before",
                                    instrument.id(), maturity)
                            .isNotEmpty();
                    assertThat(maturing.hasMaturedAsOf(maturity)).isTrue();
                    assertThat(maturing.hasMaturedAsOf(maturity.minusDays(1))).isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("the specific defect")
    class TheOriginalDefect {

        @Test
        @DisplayName("a bond maturing on a Saturday reports the Monday it actually pays")
        void saturdayMaturityReportsAdjustedDate() {
            Bond bond = bondMaturingOnSaturday();

            assertThat(SATURDAY_MATURITY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
            assertThat(bond.contractualMaturityDate()).isEqualTo(SATURDAY_MATURITY);
            assertThat(bond.maturityDate()).isEqualTo(LocalDate.of(2027, 5, 17));
            assertThat(bond.maturityDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        }

        @Test
        @DisplayName("on the contractual Saturday the bond has NOT matured and still owes principal")
        void notMaturedOnContractualDate() {
            Bond bond = bondMaturingOnSaturday();

            assertThat(bond.hasMaturedAsOf(SATURDAY_MATURITY)).isFalse();
            assertThat(bond.cashflows(SATURDAY_MATURITY)).hasSize(1);
            assertThat(bond.cashflows(SATURDAY_MATURITY).get(0).amount())
                    .isGreaterThan(Money.of("1000000", Currency.USD));
        }

        @Test
        @DisplayName("Bond and InterestRateSwap now agree on what maturityDate means")
        void instrumentsAgreeOnSemantics() {
            // Before the fix, Bond returned the unadjusted contractual date while the swap
            // returned its adjusted schedule end - two implementations of one interface
            // meaning different things.
            Bond bond = bondMaturingOnSaturday();
            InterestRateSwap swap = InterestRateSwap.builder()
                    .id("IRS").notional(Money.of("10000000", Currency.USD))
                    .fixedRate("0.04").index(FloatingRateIndex.usdSofr3M())
                    .calendar(HolidayCalendar.weekendsOnly())
                    .effectiveDate(ISSUE).maturityDate(SATURDAY_MATURITY).build();

            assertThat(bond.maturityDate()).isEqualTo(swap.maturityDate());
        }
    }

    @Nested
    @DisplayName("general properties")
    class GeneralProperties {

        @Test
        @DisplayName("every maturity falls on a business day where a schedule applies")
        void scheduledMaturitiesAreBusinessDays() {
            HolidayCalendar weekends = HolidayCalendar.weekendsOnly();

            assertThat(weekends.isBusinessDay(bondMaturingOnSaturday().maturityDate())).isTrue();
        }

        @Test
        @DisplayName("maturity always follows the instrument's start")
        void maturityFollowsStart() {
            assertThat(bondMaturingOnSaturday().maturityDate()).isAfter(ISSUE);
        }

        @Test
        @DisplayName("an instrument past maturity owes nothing at all")
        void nothingOwedAfterMaturity() {
            for (FinancialInstrument instrument : maturingInstruments()) {
                if (instrument instanceof Maturing maturing
                        && instrument instanceof CashflowGenerating generator) {

                    assertThat(generator.cashflows(maturing.maturityDate().plusYears(1)))
                            .as("%s", instrument.id())
                            .isEmpty();
                }
            }
        }
    }
}

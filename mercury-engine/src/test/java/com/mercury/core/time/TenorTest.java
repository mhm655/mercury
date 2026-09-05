package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.time.Tenor.TenorUnit;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TenorTest {

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("parses the market shorthand for each unit")
        void parsesEachUnit() {
            assertThat(Tenor.parse("1D")).isEqualTo(Tenor.days(1));
            assertThat(Tenor.parse("2W")).isEqualTo(Tenor.weeks(2));
            assertThat(Tenor.parse("3M")).isEqualTo(Tenor.months(3));
            assertThat(Tenor.parse("10Y")).isEqualTo(Tenor.years(10));
        }

        @Test
        @DisplayName("is case-insensitive and tolerates whitespace")
        void toleratesCaseAndWhitespace() {
            assertThat(Tenor.parse("  3m  ")).isEqualTo(Tenor.months(3));
            assertThat(Tenor.parse("10y")).isEqualTo(Tenor.years(10));
        }

        @Test
        @DisplayName("parsing and printing round-trip")
        void roundTrips() {
            for (String text : List.of("1D", "2W", "3M", "6M", "10Y", "30Y")) {
                assertThat(Tenor.parse(text)).hasToString(text);
            }
        }

        @Test
        @DisplayName("rejects malformed input")
        void rejectsMalformed() {
            assertThatThrownBy(() -> Tenor.parse("3X"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown tenor unit");

            assertThatThrownBy(() -> Tenor.parse("M"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Tenor.parse("threeM"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("whole number");
        }

        @Test
        @DisplayName("rejects a non-positive amount")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> Tenor.of(0, TenorUnit.MONTH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");

            assertThatThrownBy(() -> Tenor.of(-3, TenorUnit.MONTH))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("date arithmetic uses the real calendar, not the approximation")
    class DateArithmetic {

        @Test
        @DisplayName("adds using exact calendar months, not 30-day blocks")
        void addsExactMonths() {
            // 3M from 31 January is 30 April - a real month step that clamps to the end of
            // the month - not 31 January plus 90 days.
            assertThat(Tenor.months(3).addTo(LocalDate.of(2024, 1, 31)))
                    .isEqualTo(LocalDate.of(2024, 4, 30));
        }

        @Test
        @DisplayName("handles leap days")
        void handlesLeapDays() {
            assertThat(Tenor.years(1).addTo(LocalDate.of(2024, 2, 29)))
                    .isEqualTo(LocalDate.of(2025, 2, 28));
        }

        @Test
        @DisplayName("weeks and days step exactly")
        void weeksAndDays() {
            assertThat(Tenor.weeks(2).addTo(LocalDate.of(2024, 6, 1)))
                    .isEqualTo(LocalDate.of(2024, 6, 15));
            assertThat(Tenor.days(45).addTo(LocalDate.of(2024, 6, 1)))
                    .isEqualTo(LocalDate.of(2024, 7, 16));
        }

        @Test
        @DisplayName("subtractFrom mirrors addTo")
        void subtractMirrorsAdd() {
            LocalDate start = LocalDate.of(2024, 6, 15);

            assertThat(Tenor.months(6).subtractFrom(start)).isEqualTo(LocalDate.of(2023, 12, 15));
            assertThat(Tenor.years(10).subtractFrom(start)).isEqualTo(LocalDate.of(2014, 6, 15));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("sorts by approximate length, which is what curve pillars need")
        void sortsByLength() {
            List<Tenor> pillars = new java.util.ArrayList<>(List.of(
                    Tenor.parse("10Y"), Tenor.parse("1D"), Tenor.parse("6M"),
                    Tenor.parse("2Y"), Tenor.parse("1W"), Tenor.parse("3M")));

            java.util.Collections.sort(pillars);

            assertThat(pillars).containsExactly(
                    Tenor.parse("1D"), Tenor.parse("1W"), Tenor.parse("3M"),
                    Tenor.parse("6M"), Tenor.parse("2Y"), Tenor.parse("10Y"));
        }

        @Test
        @DisplayName("compares across units")
        void comparesAcrossUnits() {
            assertThat(Tenor.parse("12M")).isGreaterThan(Tenor.parse("6M"));
            assertThat(Tenor.parse("1Y")).isGreaterThan(Tenor.parse("30D"));
            assertThat(Tenor.parse("2W")).isGreaterThan(Tenor.parse("7D"));
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("amount and unit together are the identity")
        void amountAndUnitAreIdentity() {
            assertThat(Tenor.months(12)).isEqualTo(Tenor.months(12));

            // 12M and 1Y are the same duration but different quotes, so they are different
            // tenors and different map keys. Curves are built from one or the other.
            assertThat(Tenor.months(12)).isNotEqualTo(Tenor.years(1));
        }
    }
}

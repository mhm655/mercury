package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every instrument uses entity equality: same {@code InstrumentId} means the same instrument.
 *
 * <h2>Why this test exists</h2>
 * The hierarchy carried two contradictory notions of sameness. {@code Stock},
 * {@code FxForward} and {@code EuropeanOption} were records and inherited component-wise
 * value equality; {@code Bond} and {@code InterestRateSwap} compared on id alone. Nothing
 * documented either choice, and a caller holding {@code FinancialInstrument} could not know
 * which it would get.
 *
 * <p>Entity semantics is the right answer for this domain, not merely the consistent one.
 * Market data is keyed by {@code InstrumentId} and positions are keyed by it, so two objects
 * with the same id denote the same real-world instrument - one possibly a stale or amended
 * copy. Under value equality an amended instrument would stop equalling the position that
 * referenced it, and a {@code Map} keyed by instrument would quietly grow a second entry.
 */
class InstrumentIdentityTest {

    private static final LocalDate ISSUE = LocalDate.of(2024, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2029, 1, 15);
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    /**
     * For each instrument type, a factory taking an id. Every factory also varies the
     * instrument's economics, so a test that passes could not be passing by coincidence of
     * two instruments being structurally identical.
     */
    private static Map<String, Function<String, FinancialInstrument>> factories(boolean variant) {
        return Map.of(
                "Stock", id -> Stock.of(id, variant ? Currency.EUR : Currency.USD),
                "Bond", id -> Bond.builder().id(id)
                        .faceValue(Money.of(variant ? "9999999" : "1000000", Currency.USD))
                        .couponRate(variant ? "0.99" : "0.05")
                        .couponFrequency(variant ? Frequency.MONTHLY : Frequency.ANNUAL)
                        .calendar(HolidayCalendar.alwaysOpen())
                        .issueDate(ISSUE).maturityDate(variant ? MATURITY.plusYears(10) : MATURITY)
                        .build(),
                "FxForward", id -> new FxForward(InstrumentId.of(id),
                        CurrencyPair.parse("EUR/USD"),
                        new java.math.BigDecimal(variant ? "500000" : "1000000"),
                        new java.math.BigDecimal(variant ? "1.25" : "1.08"),
                        variant ? MATURITY : LocalDate.of(2025, 3, 17)),
                "EuropeanOption", id -> new EuropeanOption(InstrumentId.of(id), AAPL,
                        variant ? OptionType.PUT : OptionType.CALL,
                        Price.of(variant ? "500" : "200"),
                        variant ? MATURITY : LocalDate.of(2025, 1, 17),
                        variant ? 50 : 100, Currency.USD),
                "InterestRateSwap", id -> InterestRateSwap.builder().id(id)
                        .notional(Money.of(variant ? "5000000" : "10000000", Currency.USD))
                        .fixedRate(variant ? "0.09" : "0.04")
                        .index(FloatingRateIndex.usdSofr3M())
                        .calendar(HolidayCalendar.alwaysOpen())
                        .effectiveDate(ISSUE)
                        .maturityDate(variant ? MATURITY.plusYears(5) : MATURITY)
                        .build());
    }

    @Test
    @DisplayName("same id means equal, however much the terms differ")
    void sameIdMeansEqual() {
        factories(false).forEach((type, plain) -> {
            FinancialInstrument original = plain.apply("SAME-ID");
            FinancialInstrument amended = factories(true).get(type).apply("SAME-ID");

            assertThat(original)
                    .as("%s: two objects with the same id must be the same instrument", type)
                    .isEqualTo(amended);
            assertThat(original)
                    .as("%s: equal instruments must share a hash code", type)
                    .hasSameHashCodeAs(amended);
        });
    }

    @Test
    @DisplayName("different ids mean unequal, however identical the terms")
    void differentIdsMeanUnequal() {
        factories(false).forEach((type, factory) ->
                assertThat(factory.apply("ID-A"))
                        .as("%s: distinct ids are distinct instruments", type)
                        .isNotEqualTo(factory.apply("ID-B")));
    }

    @Test
    @DisplayName("an amended instrument still matches its position key")
    void amendedInstrumentStillMatchesItsPositionKey() {
        // The practical consequence. Under value equality this map lookup returns null after
        // an amendment, and the position silently detaches from its instrument.
        FinancialInstrument original = factories(false).get("Bond").apply("UST-5Y");
        FinancialInstrument amended = factories(true).get("Bond").apply("UST-5Y");

        Map<FinancialInstrument, String> positions = new HashMap<>();
        positions.put(original, "long 1,000,000");

        assertThat(positions.get(amended)).isEqualTo("long 1,000,000");
        assertThat(positions).hasSize(1);
    }

    @Test
    @DisplayName("a set of instruments deduplicates by id")
    void setsDeduplicateById() {
        factories(false).forEach((type, plain) -> {
            var set = new HashSet<FinancialInstrument>();
            set.add(plain.apply("DUP"));
            set.add(factories(true).get(type).apply("DUP"));

            assertThat(set).as("%s", type).hasSize(1);
        });
    }

    @Test
    @DisplayName("equality is reflexive, symmetric and null-safe across every type")
    void equalsContractHolds() {
        factories(false).forEach((type, factory) -> {
            FinancialInstrument a = factory.apply("X");
            FinancialInstrument b = factory.apply("X");

            assertThat(a.equals(a)).as("%s reflexive", type).isTrue();
            assertThat(a.equals(b) && b.equals(a)).as("%s symmetric", type).isTrue();
            assertThat(a.equals(null)).as("%s null-safe", type).isFalse();
            assertThat(a.equals("not an instrument")).as("%s type-safe", type).isFalse();
        });
    }

    @Test
    @DisplayName("instruments of different types never compare equal, even sharing an id")
    void differentTypesNeverEqual() {
        List<FinancialInstrument> sameId = factories(false).values().stream()
                .map(factory -> factory.apply("COLLIDE")).toList();

        for (int i = 0; i < sameId.size(); i++) {
            for (int j = i + 1; j < sameId.size(); j++) {
                assertThat(sameId.get(i))
                        .as("%s vs %s", sameId.get(i).getClass().getSimpleName(),
                                sameId.get(j).getClass().getSimpleName())
                        .isNotEqualTo(sameId.get(j));
            }
        }
    }
}

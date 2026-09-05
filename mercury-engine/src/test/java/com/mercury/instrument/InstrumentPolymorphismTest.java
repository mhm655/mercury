package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.HolidayCalendar;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the architecture rather than any one instrument.
 *
 * <p>The project's central claim is that instruments can be handled uniformly without
 * {@code instanceof} chains, and that capabilities are asked for rather than assumed. These
 * tests exercise a heterogeneous portfolio through the interfaces alone - if the
 * abstractions were leaky, this is where it would show.
 */
class InstrumentPolymorphismTest {

    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2029, 1, 15);
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    /** One of each instrument type, held as the base interface. */
    private static List<FinancialInstrument> allInstruments() {
        return List.of(
                Stock.of("AAPL", Currency.USD),
                Bond.builder()
                        .id("UST-5Y").faceValue(Money.of("1000000", Currency.USD))
                        .couponRate("0.05").calendar(HolidayCalendar.alwaysOpen())
                        .issueDate(VALUATION).maturityDate(MATURITY).build(),
                FxForward.buy("FWD-EURUSD", CurrencyPair.parse("EUR/USD"), "1000000", "1.08",
                        LocalDate.of(2024, 9, 15)),
                EuropeanOption.call("AAPL-C-200", AAPL, Price.of("200"),
                        LocalDate.of(2025, 1, 15), Currency.USD),
                InterestRateSwap.builder()
                        .id("IRS-USD-5Y").notional(Money.of("10000000", Currency.USD))
                        .fixedRate("0.0425").payingFixed()
                        .index(FloatingRateIndex.usdSofr3M())
                        .calendar(HolidayCalendar.alwaysOpen())
                        .effectiveDate(VALUATION).maturityDate(MATURITY).build());
    }

    @Test
    @DisplayName("every instrument answers the base interface without any type check")
    void baseInterfaceIsSufficientForGenericWork() {
        for (FinancialInstrument instrument : allInstruments()) {
            assertThat(instrument.id()).isNotNull();
            assertThat(instrument.currency()).isNotNull();
            assertThat(instrument.assetClass()).isNotNull();
            assertThat(instrument.tradability()).isNotNull();
            assertThat(instrument.description()).isNotBlank();
        }
    }

    @Test
    @DisplayName("exposure buckets by asset class with no knowledge of concrete types")
    void bucketsByAssetClassPolymorphically() {
        // A sketch of what ExposureCalculator does at M7. Note there is no instanceof and
        // no switch on instrument type - only a property every instrument declares.
        Map<AssetClass, Integer> countByClass = new EnumMap<>(AssetClass.class);
        for (FinancialInstrument instrument : allInstruments()) {
            countByClass.merge(instrument.assetClass(), 1, Integer::sum);
        }

        assertThat(countByClass).containsEntry(AssetClass.EQUITY, 2)  // stock + option
                .containsEntry(AssetClass.RATES, 2)                    // bond + swap
                .containsEntry(AssetClass.FX, 1);                      // forward
    }

    @Test
    @DisplayName("venue routing uses a declared property, not a type check")
    void routesToVenueWithoutTypeChecks() {
        // This is the mechanism behind DESIGN_PROPOSAL section A2.1: OTC instruments must
        // not reach the matching engine, and the routing decision is data on the
        // instrument rather than a chain of instanceof tests in the router.
        List<FinancialInstrument> exchangeTraded = allInstruments().stream()
                .filter(i -> i.tradability().isExchangeTraded())
                .toList();
        List<FinancialInstrument> otc = allInstruments().stream()
                .filter(i -> i.tradability().hasCounterpartyRisk())
                .toList();

        assertThat(exchangeTraded).hasSize(2);  // stock, bond
        assertThat(otc).hasSize(3);             // forward, option, swap
        assertThat(exchangeTraded).doesNotContainAnyElementsOf(otc);
    }

    @Test
    @DisplayName("capabilities are asked for, and only answered where they genuinely hold")
    void capabilitiesAreOptedInto() {
        // Pattern matching for instanceof against a *capability* is not the anti-pattern
        // the design warns about. The forbidden thing is branching on concrete instrument
        // types, which forces every new instrument to edit existing code. Asking whether
        // something can generate cashflows is open-ended: a new instrument that implements
        // the interface is picked up here with no change at all.
        Money totalKnownCashflows = Money.zero(Currency.USD);
        int cashflowBearing = 0;

        for (FinancialInstrument instrument : allInstruments()) {
            if (instrument instanceof CashflowGenerating generator) {
                cashflowBearing++;
                for (Cashflow cashflow : generator.cashflows(VALUATION)) {
                    if (cashflow.amount().currency() == Currency.USD) {
                        totalKnownCashflows = totalKnownCashflows.plus(cashflow.amount());
                    }
                }
            }
        }

        // The bond and the FX forward. Not the swap: half its cashflows need a curve.
        assertThat(cashflowBearing).isEqualTo(2);

        // Bond:    ten semi-annual coupons of 25,000, plus 1,000,000 principal = +1,250,000
        // Forward: the USD leg of buying 1,000,000 EUR at 1.08              = -1,080,000
        //                                                                      -----------
        //                                                                        +170,000
        // The forward's EUR leg is excluded by the currency filter above, which is the
        // point: summing across currencies without an FX rate is meaningless, and Money
        // refuses to do it.
        assertThat(totalKnownCashflows).isEqualTo(Money.of("170000.00", Currency.USD));
    }

    @Test
    @DisplayName("only the instruments that expire report a maturity")
    void maturityIsACapability() {
        List<FinancialInstrument> maturing = allInstruments().stream()
                .filter(Maturing.class::isInstance)
                .toList();

        // Everything except the stock. Modelling maturity on the base interface would have
        // forced an Optional.empty() onto the equity and an empty case onto every caller.
        assertThat(maturing).hasSize(4);
        assertThat(maturing).allSatisfy(instrument ->
                assertThat(((Maturing) instrument).maturityDate()).isAfter(VALUATION));
    }

    @Test
    @DisplayName("instrument identities are unique across the portfolio")
    void identitiesAreUnique() {
        List<InstrumentId> ids = allInstruments().stream().map(FinancialInstrument::id).toList();

        assertThat(ids).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    @DisplayName("the base interface is not sealed, so instruments stay an open set")
    void interfaceIsOpenForExtension() {
        // The counterpart to DomainIdTest.sealedHierarchyIsComplete. Identifiers are a
        // closed set and are sealed; instruments must stay open, because the M15
        // extensibility proof adds a sixth from outside this package. If someone sealed
        // this interface, that proof would become impossible and this test would say so.
        assertThat(FinancialInstrument.class.isSealed())
                .as("FinancialInstrument must remain open for extension")
                .isFalse();
        assertThat(CashflowGenerating.class.isSealed()).isFalse();
        assertThat(OptionTerms.class.isSealed()).isFalse();
    }

    @Test
    @DisplayName("a new instrument type needs no change to generic handling")
    void newInstrumentTypeRequiresNoChanges() {
        // A minimal sixth instrument, declared here rather than in the package. Every
        // generic operation above accepts it with no modification anywhere - which is the
        // property the M15 proof will demonstrate on a real instrument in a real commit.
        record Commodity(InstrumentId id, String name) implements FinancialInstrument {
            @Override
            public Currency currency() {
                return Currency.USD;
            }

            @Override
            public AssetClass assetClass() {
                return AssetClass.EQUITY;
            }

            @Override
            public TradabilityProfile tradability() {
                return TradabilityProfile.EXCHANGE_TRADED;
            }

            @Override
            public String description() {
                return name;
            }
        }

        FinancialInstrument gold = new Commodity(InstrumentId.of("XAU"), "Gold");

        assertThat(gold.tradability().isExchangeTraded()).isTrue();
        assertThat(gold.assetClass()).isEqualTo(AssetClass.EQUITY);
        assertThat(gold).isNotInstanceOf(Maturing.class);
        assertThat(gold).isNotInstanceOf(CashflowGenerating.class);
    }
}

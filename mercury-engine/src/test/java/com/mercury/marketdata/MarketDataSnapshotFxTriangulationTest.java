package com.mercury.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketDataSnapshot#fxRate}'s opt-in triangulation through a single stated vehicle
 * currency - M22. The plain direct/inverse resolution is already covered by
 * {@code DiscountedCashflowModelTest.FxRates}; this file is triangulation's own, since a
 * vehicle currency is a property of the snapshot itself, not of any one pricer that reads it.
 */
class MarketDataSnapshotFxTriangulationTest {

    private static final LocalDate VALUATION = LocalDate.of(2023, 1, 15);
    private static final CurrencyPair GBPUSD = CurrencyPair.parse("GBP/USD");
    private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");
    private static final CurrencyPair GBPEUR = CurrencyPair.parse("GBP/EUR");

    @Test
    @DisplayName("triangulates through the stated vehicle when no direct or inverse pair exists")
    void triangulatesThroughTheStatedVehicleWhenNoDirectOrInversePairExists() {
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                .vehicleCurrency(Currency.USD)
                .fxRate(GBPUSD, 1.25)
                .fxRate(EURUSD, 1.10)
                .build();

        double expected = 1.25 / 1.10; // GBP/USD divided by EUR/USD, both against the vehicle
        assertThat(market.fxRate(Currency.GBP, Currency.EUR)).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("a direct pair is always preferred over triangulation")
    void directPairIsPreferredOverTriangulation() {
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                .vehicleCurrency(Currency.USD)
                .fxRate(GBPEUR, 0.85) // the stated cross, deliberately not equal to GBP/USD ÷ EUR/USD
                .fxRate(GBPUSD, 1.25)
                .fxRate(EURUSD, 1.10)
                .build();

        assertThat(market.fxRate(Currency.GBP, Currency.EUR)).isCloseTo(0.85, within(1e-12));
    }

    @Test
    @DisplayName("no triangulation happens without a stated vehicle currency")
    void noTriangulationWithoutAStatedVehicle() {
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                .fxRate(GBPUSD, 1.25)
                .fxRate(EURUSD, 1.10)
                .build();

        assertThatThrownBy(() -> market.fxRate(Currency.GBP, Currency.EUR))
                .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                .hasMessageContaining("fx:GBP/EUR")
                .hasMessageNotContaining("triangulat");
    }

    @Test
    @DisplayName("a missing leg through the vehicle names the vehicle currency in the failure")
    void missingLegThrowsNamingTheAttemptedVehicle() {
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                .vehicleCurrency(Currency.USD)
                .fxRate(GBPUSD, 1.25)
                // no EUR/USD, so triangulating GBP -> EUR through USD cannot complete
                .build();

        assertThatThrownBy(() -> market.fxRate(Currency.GBP, Currency.EUR))
                .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                .hasMessageContaining("fx:GBP/EUR")
                .hasMessageContaining("triangulating through USD");
    }

    @Test
    @DisplayName("a vehicle equal to either currency is never consulted")
    void vehicleEqualToFromOrToIsNotConsulted() {
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                .vehicleCurrency(Currency.GBP)
                .fxRate(EURUSD, 1.10)
                .build();

        assertThatThrownBy(() -> market.fxRate(Currency.GBP, Currency.EUR))
                .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                .hasMessageContaining("fx:GBP/EUR")
                .hasMessageNotContaining("triangulat");
    }
}

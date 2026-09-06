package com.mercury.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.instrument.AssetClass;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.Stock;
import com.mercury.instrument.TradabilityProfile;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.SpotPriceModel;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the dispatch architecture rather than any one model.
 *
 * <p>The project's central claim is that a new instrument costs one class, one model and one
 * registration line, with no {@code instanceof} chain and no Visitor to edit. These tests
 * exercise that claim directly - including by adding a whole new instrument type inline and
 * pricing it through the same service.
 */
class PricingServiceTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);

    private static PricingService service() {
        return PricingService.builder()
                .register(new SpotPriceModel())
                .register(new BlackScholesModel())
                .build();
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder()
                .spot(AAPL, 200.0)
                .volatility(AAPL, 0.25)
                .discountRate(Currency.USD, 0.04)
                .build();
    }

    @Nested
    @DisplayName("polymorphic dispatch")
    class Dispatch {

        @Test
        @DisplayName("two unrelated instruments price through one call, with no type check")
        void dispatchesWithoutTypeChecks() {
            // The heterogeneous list is the point: the caller holds FinancialInstrument and
            // never mentions Stock or EuropeanOption.
            List<FinancialInstrument> portfolio = List.of(
                    Stock.of("AAPL", Currency.USD),
                    EuropeanOption.call("AAPL-C-200", AAPL, Price.of("200"),
                            VALUATION.plusYears(1), Currency.USD));

            List<ValuationResult> results = portfolio.stream()
                    .map(instrument -> service().price(instrument, market(), VALUATION))
                    .toList();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).value()).isCloseTo(200.0, within(1e-9));
            assertThat(results.get(0).model()).isEqualTo(SpotPriceModel.NAME);
            assertThat(results.get(1).model()).isEqualTo(BlackScholesModel.NAME);
            assertThat(results.get(1).value()).isBetween(15.0, 30.0);
        }

        @Test
        @DisplayName("the result records which model produced it")
        void resultCarriesItsModel() {
            ValuationResult result = service().price(
                    Stock.of("AAPL", Currency.USD), market(), VALUATION);

            assertThat(result.model()).isEqualTo(ModelName.of("spot"));
            assertThat(result.currency()).isEqualTo(Currency.USD);
        }

        @Test
        @DisplayName("an unregistered instrument type fails with a useful message")
        void unregisteredTypeThrows() {
            PricingService onlyStocks = PricingService.builder()
                    .register(new SpotPriceModel()).build();
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-200", AAPL, Price.of("200"), VALUATION.plusYears(1), Currency.USD);

            assertThatThrownBy(() -> onlyStocks.price(option, market(), VALUATION))
                    .isInstanceOf(PricingService.NoPricingModelException.class)
                    .hasMessageContaining("EuropeanOption")
                    .hasMessageContaining("Stock")
                    .hasMessageContaining("single register");
        }
    }

    @Nested
    @DisplayName("multiple models per instrument")
    class MultipleModels {

        /** A deliberately crude second model, standing in for the binomial tree at M15. */
        private static final class IntrinsicValueModel implements PricingModel<EuropeanOption> {
            static final ModelName NAME = ModelName.of("intrinsic");

            @Override
            public Class<EuropeanOption> instrumentType() {
                return EuropeanOption.class;
            }

            @Override
            public ModelName name() {
                return NAME;
            }

            @Override
            public ValuationResult price(EuropeanOption option, MarketDataSnapshot market,
                                         LocalDate asOf) {
                return new ValuationResult(
                        option.intrinsicValue(market.spot(option.underlyingId())),
                        option.currency(), NAME);
            }
        }

        @Test
        @DisplayName("one instrument can carry two models, selected by name")
        void twoModelsForOneInstrument() {
            // The requirement that rules out instrument.price(): an option must be priceable
            // two ways so the two can be cross-checked.
            PricingService both = PricingService.builder()
                    .register(new SpotPriceModel())
                    .register(new BlackScholesModel())
                    .register(new IntrinsicValueModel())
                    .build();
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-150", AAPL, Price.of("150"), VALUATION.plusYears(1), Currency.USD);

            double blackScholes = both.price(
                    option, BlackScholesModel.NAME, market(), VALUATION).value();
            double intrinsic = both.price(
                    option, IntrinsicValueModel.NAME, market(), VALUATION).value();

            // Time value is positive, so Black-Scholes must exceed intrinsic on a live option.
            assertThat(intrinsic).isCloseTo(50.0, within(1e-9));
            assertThat(blackScholes).isGreaterThan(intrinsic);
            assertThat(both.modelsFor(EuropeanOption.class))
                    .containsExactlyInAnyOrder(BlackScholesModel.NAME, IntrinsicValueModel.NAME);
        }

        @Test
        @DisplayName("the first model registered becomes the default")
        void firstRegisteredIsDefault() {
            PricingService service = PricingService.builder()
                    .register(new BlackScholesModel())
                    .register(new IntrinsicValueModel())
                    .build();
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-150", AAPL, Price.of("150"), VALUATION.plusYears(1), Currency.USD);

            assertThat(service.price(option, market(), VALUATION).model())
                    .isEqualTo(BlackScholesModel.NAME);
        }

        @Test
        @DisplayName("the default can be overridden explicitly")
        void defaultCanBeOverridden() {
            // Explicit rather than "whichever was registered last", so wiring order cannot
            // silently change valuations.
            PricingService service = PricingService.builder()
                    .register(new BlackScholesModel())
                    .register(new IntrinsicValueModel())
                    .withDefault(EuropeanOption.class, IntrinsicValueModel.NAME)
                    .build();
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-150", AAPL, Price.of("150"), VALUATION.plusYears(1), Currency.USD);

            assertThat(service.price(option, market(), VALUATION).model())
                    .isEqualTo(IntrinsicValueModel.NAME);
        }

        @Test
        @DisplayName("asking for a model that is not registered names what is")
        void unknownModelNameThrows() {
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-150", AAPL, Price.of("150"), VALUATION.plusYears(1), Currency.USD);

            assertThatThrownBy(() -> service()
                    .price(option, ModelName.of("binomial"), market(), VALUATION))
                    .isInstanceOf(PricingService.NoPricingModelException.class)
                    .hasMessageContaining("binomial")
                    .hasMessageContaining("black-scholes");
        }

        @Test
        @DisplayName("registering the same name twice for one type is rejected")
        void duplicateModelNameRejected() {
            assertThatThrownBy(() -> PricingService.builder()
                    .register(new BlackScholesModel())
                    .register(new BlackScholesModel())
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName("defaulting to an unregistered model is rejected at build time")
        void badDefaultRejected() {
            assertThatThrownBy(() -> PricingService.builder()
                    .register(new BlackScholesModel())
                    .withDefault(EuropeanOption.class, ModelName.of("nonexistent")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not registered");
        }
    }

    @Nested
    @DisplayName("open-closed: adding an instrument touches nothing existing")
    class OpenForExtension {

        /** A sixth instrument type, declared here rather than in the engine. */
        private record Commodity(InstrumentId id, Currency currency) implements FinancialInstrument {
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
                return "commodity " + id;
            }
        }

        private static final class CommodityModel implements PricingModel<Commodity> {
            static final ModelName NAME = ModelName.of("commodity-spot");

            @Override
            public Class<Commodity> instrumentType() {
                return Commodity.class;
            }

            @Override
            public ModelName name() {
                return NAME;
            }

            @Override
            public ValuationResult price(Commodity commodity, MarketDataSnapshot market,
                                         LocalDate asOf) {
                return new ValuationResult(market.spot(commodity.id()), commodity.currency(), NAME);
            }
        }

        @Test
        @DisplayName("a new instrument type needs one class, one model and one registration")
        void newInstrumentCostsThreeThings() {
            // A rehearsal for the M15 proof. Note what did NOT change to make this work:
            // no interface gained a method, no switch gained a branch, no existing model or
            // test was touched. That is the whole architectural claim, exercised.
            InstrumentId gold = InstrumentId.of("XAU");
            PricingService extended = PricingService.builder()
                    .register(new SpotPriceModel())
                    .register(new BlackScholesModel())
                    .register(new CommodityModel())
                    .build();

            MarketDataSnapshot withGold = MarketDataSnapshot.builder()
                    .spot(AAPL, 200.0).volatility(AAPL, 0.25)
                    .discountRate(Currency.USD, 0.04)
                    .spot(gold, 2400.0)
                    .build();

            List<FinancialInstrument> mixed = List.of(
                    Stock.of("AAPL", Currency.USD),
                    new Commodity(gold, Currency.USD));

            List<Double> values = mixed.stream()
                    .map(instrument -> extended.price(instrument, withGold, VALUATION).value())
                    .toList();

            assertThat(values).containsExactly(200.0, 2400.0);
            assertThat(extended.canPrice(Commodity.class)).isTrue();
        }

        @Test
        @DisplayName("the pre-existing service is unaffected by the new type")
        void existingServiceUnchanged() {
            assertThat(service().canPrice(Commodity.class)).isFalse();
            assertThat(service().canPrice(Stock.class)).isTrue();
        }
    }
}

package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.SpotPriceModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoricalVaRCalculatorTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final Stock AAPL_STOCK = Stock.of("AAPL", Currency.USD);

    private static HistoricalVaRCalculator calculator() {
        SensitivityCalculator sensitivities = new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder().register(new SpotPriceModel()).build(),
                InstrumentCatalog.of(AAPL_STOCK)));
        return new HistoricalVaRCalculator(sensitivities);
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION).spot(AAPL, 100.0).build();
    }

    private static Portfolio book(long quantity) {
        return Portfolio.builder(BOOK, Currency.USD).position(AAPL, quantity).build();
    }

    /** One scaleSpot shock per day, from a list of daily factors (1.02 = a 2% up day). */
    private static List<MarketShock> scenariosFromDailyFactors(double... dailyFactors) {
        List<MarketShock> shocks = new ArrayList<>();
        for (double factor : dailyFactors) {
            shocks.add(MarketShock.scaleSpot(AAPL, factor));
        }
        return shocks;
    }

    @Test
    void wholeNumberExampleFromTheJavadoc() {
        // Ten scenarios, 90% confidence -> k = ceil(0.10 * 10) = 1 -> the single worst
        // observation. Ten daily moves from -10% to +8% on 1,000 shares worth 100,000: the
        // worst day is -10%, a loss of 10,000.
        Portfolio portfolio = book(1_000);
        List<MarketShock> tenDays = scenariosFromDailyFactors(
                0.90, 0.97, 1.01, 1.03, 0.99, 1.02, 0.95, 1.08, 0.98, 1.00);

        Money var = calculator().valueAtRisk(portfolio, tenDays, market(), VALUATION, 0.90);

        assertThat(var).isEqualTo(Money.of("10000.00", Currency.USD));
    }

    @Test
    void aHundredScenariosAtNinetyFivePercentTakesTheFifthWorst() {
        // 100 scenarios, evenly spaced factors from 0.80 to 1.19 (in steps of 0.004) -
        // sorted, the fifth-worst is the fifth entry from the bottom. Built explicitly rather
        // than generated randomly, so the expected answer is exact and checkable by hand.
        //
        // This is also the regression test for a real bug: (1 - 0.95) * 100 is mathematically
        // exactly 5.0, but computes as 5.000000000000001 in double arithmetic, and an
        // unguarded Math.ceil of that rounds up to 6 - silently taking the sixth-worst
        // scenario (0.82, an 18% loss) instead of the fifth (0.816, 18.4%). The assertion
        // below is exact rather than loosely toleranced specifically so this class of error
        // cannot hide behind a wide tolerance band.
        double[] factors = new double[100];
        for (int i = 0; i < 100; i++) {
            factors[i] = 0.80 + i * 0.004; // 0.80, 0.804, ..., up to 1.196
        }
        Portfolio portfolio = book(1_000);
        // The 5th smallest factor (index 4, 0-based) is 0.80 + 4*0.004 = 0.816 -> an 18.4% loss.
        Money expected = Money.of("18400.00", Currency.USD);

        Money var = calculator().valueAtRisk(
                portfolio, scenariosFromDailyFactors(factors), market(), VALUATION, 0.95);

        assertThat(var.amount().doubleValue()).isCloseTo(expected.amount().doubleValue(),
                org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    void allGainsReportZeroValueAtRisk() {
        // Every scenario is a gain, so even the worst-at-this-confidence outcome is a gain -
        // there is no loss to report, not a negative one.
        Portfolio portfolio = book(1_000);
        List<MarketShock> allUp = scenariosFromDailyFactors(1.01, 1.02, 1.03, 1.04, 1.05);

        Money var = calculator().valueAtRisk(portfolio, allUp, market(), VALUATION, 0.90);

        assertThat(var).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void aFlatBookHasNoValueAtRisk() {
        Portfolio empty = Portfolio.builder(BOOK, Currency.USD).build();
        List<MarketShock> someDays = scenariosFromDailyFactors(0.90, 1.10);

        Money var = calculator().valueAtRisk(empty, someDays, market(), VALUATION, 0.95);

        assertThat(var).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void aLargerConfidenceLevelNeverReportsALargerLoss() {
        // Raising confidence moves the rank further into the tail (never a better outcome),
        // so VaR is monotone non-decreasing in confidence level.
        Portfolio portfolio = book(1_000);
        List<MarketShock> days = scenariosFromDailyFactors(
                0.85, 0.90, 0.93, 0.97, 0.99, 1.00, 1.02, 1.04, 1.06, 1.09);

        Money var90 = calculator().valueAtRisk(portfolio, days, market(), VALUATION, 0.90);
        Money var99 = calculator().valueAtRisk(portfolio, days, market(), VALUATION, 0.99);

        assertThat(var99.isGreaterThan(var90) || var99.equals(var90)).isTrue();
    }

    @Test
    void rejectsAnEmptyScenarioList() {
        assertThatThrownBy(() -> calculator()
                .valueAtRisk(book(1_000), List.of(), market(), VALUATION, 0.95))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void rejectsAConfidenceLevelOutsideZeroAndOne() {
        List<MarketShock> oneDay = scenariosFromDailyFactors(0.95);

        assertThatThrownBy(() -> calculator()
                .valueAtRisk(book(1_000), oneDay, market(), VALUATION, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator()
                .valueAtRisk(book(1_000), oneDay, market(), VALUATION, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.MonteCarloOptionModel;
import com.mercury.simulation.MonteCarloVaRCalculator;
import com.mercury.simulation.SimulationWorkers;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * How Monte Carlo scales from 1 to 12 worker threads on a 6-core, 12-thread machine.
 *
 * <p>`docs/DESIGN_PROPOSAL.md` section 5.6 predicted this before a line of it existed:
 * near-linear to about 6 workers, then a sharp knee, with hyperthreads adding perhaps 15-30%
 * on floating-point work rather than doubling it. These two benchmarks exist to test that
 * prediction, and {@code docs/BENCHMARKS.md} reports whatever they actually show.
 *
 * <p>Two workloads with deliberately different shapes:
 * <ul>
 *   <li>{@link #optionPrice} - a million GBM draws and payoffs. Almost pure arithmetic, almost
 *       no allocation: the closest thing here to the ideal embarrassingly-parallel case.</li>
 *   <li>{@link #valueAtRisk} - every path revalues a small option book, allocating a shocked
 *       snapshot, valuation lines and {@code Money} values per path, then one serial sort of
 *       every P&amp;L at the end. Allocation rate and that serial tail are exactly what the
 *       design doc expected to bend the curve early.</li>
 * </ul>
 *
 * <p>Both give the same answer at every worker count (see {@code PathBlocks}) - so the
 * figures below compare identical work, not a faster run doing less of it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class MonteCarloScalingBenchmark {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);

    private static final int OPTION_PATHS = 1_000_000;
    private static final int VAR_PATHS = 50_000;

    @Param({"1", "2", "4", "6", "8", "12"})
    public int workers;

    private SimulationWorkers simulationWorkers;
    private MonteCarloOptionModel optionModel;
    private MonteCarloVaRCalculator varCalculator;
    private EuropeanOption call;
    private Portfolio book;
    private MarketDataSnapshot market;

    @Setup(Level.Trial)
    public void setUp() {
        simulationWorkers = SimulationWorkers.parallel(workers);

        Stock stock = Stock.of("AAPL", Currency.USD);
        call = EuropeanOption.call("AAPL-C-200", AAPL, Price.of("200"), VALUATION.plusYears(1), Currency.USD);
        EuropeanOption put = EuropeanOption.put("AAPL-P-180", AAPL, Price.of("180"), VALUATION.plusMonths(6), Currency.USD);
        EuropeanOption farCall = EuropeanOption.call("AAPL-C-240", AAPL, Price.of("240"), VALUATION.plusYears(2), Currency.USD);

        market = MarketDataSnapshot.builder(VALUATION)
                .spot(AAPL, 200.0)
                .volatility(AAPL, 0.25)
                .discountRate(Currency.USD, 0.04)
                .build();
        book = Portfolio.builder(PortfolioId.of("BENCH"), Currency.USD)
                .position(AAPL, 1_000)
                .position(call.id(), 20)
                .position(put.id(), -15)
                .position(farCall.id(), 10)
                .build();

        SensitivityCalculator sensitivities = new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder().register(new SpotPriceModel()).register(new BlackScholesModel()).build(),
                InstrumentCatalog.of(stock, call, put, farCall)));

        optionModel = new MonteCarloOptionModel(42, OPTION_PATHS, simulationWorkers);
        varCalculator = new MonteCarloVaRCalculator(sensitivities, 7, simulationWorkers);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        simulationWorkers.close();
    }

    @Benchmark
    public double optionPrice() {
        return optionModel.price(call, market, VALUATION).value();
    }

    @Benchmark
    public Object valueAtRisk() {
        return varCalculator.simulate(book, AAPL, 0.0, 0.25, 1.0 / 365, VAR_PATHS, market, VALUATION, 0.99);
    }
}

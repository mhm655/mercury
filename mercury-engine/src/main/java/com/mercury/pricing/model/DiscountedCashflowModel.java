package com.mercury.pricing.model;

import com.mercury.core.money.Currency;
import com.mercury.core.time.DayCountConvention;
import com.mercury.instrument.Cashflow;
import com.mercury.instrument.CashflowGenerating;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Values any instrument whose future payments are contractually known, by discounting them.
 *
 * <h2>The seam paying off</h2>
 * This is what {@link CashflowGenerating} was designed for. A bond and an FX forward have
 * almost nothing in common as products, and the same twenty lines value both, because both
 * can answer one question: what do you pay, and when. Without that interface this arithmetic
 * would exist twice and would drift.
 *
 * <h2>Why this is not a Template Method</h2>
 * The design proposal listed Template Method as the pattern here, with per-instrument
 * subclasses overriding a cashflow-projection step. Writing it showed there is <b>no varying
 * step to override</b>: bonds and FX forwards both expose {@code cashflows(LocalDate)} and
 * differ in nothing the discounting cares about. A base class with two subclasses that add
 * only a type token and a name would be ceremony.
 *
 * <p>So it is one generic model, parameterised by the class it prices and registered once per
 * instrument type. Java's intersection bound {@code <T extends FinancialInstrument &
 * CashflowGenerating>} expresses the requirement exactly - the compiler will not let this be
 * registered for an instrument that cannot produce cashflows.
 *
 * <p>A floating swap leg is precisely the case that <em>would</em> need a varying step, since
 * its coupons must be projected from a curve before they can be discounted. That is why it
 * does not implement {@code CashflowGenerating}, and why it will get its own model at M6 -
 * at which point a shared skeleton may genuinely be worth extracting. Patterns are cheaper to
 * add when a second case proves they are needed than to remove once they are load-bearing.
 *
 * <h2>The mathematics</h2>
 * <pre>
 *   PV = sum over cashflows of   amount x e^(-r_ccy T) x fx(ccy -&gt; instrument ccy)
 * </pre>
 *
 * Each cashflow is discounted at <em>its own</em> currency's rate and then converted at spot,
 * which is what makes an FX forward come out right: its two legs settle in different
 * currencies and must be discounted on different curves before being compared.
 *
 * <p>{@code T} is measured ACT/365F, matching the convention {@link BlackScholesModel} uses,
 * so an option and a bond in one portfolio agree about how long a year is.
 *
 * <h2>Simplifications, named</h2>
 * <ul>
 *   <li><b>Flat discounting.</b> One rate per currency for every maturity. A real curve
 *       arrives at M5b; until then the ten-year point discounts at the overnight rate.</li>
 *   <li><b>Continuous compounding.</b> {@code e^-rT} rather than {@code (1+r)^-T}. The two
 *       differ by roughly {@code r^2 T / 2}, so quoting a rate on the wrong basis is a real
 *       error - the convention is fixed here and stated on {@code MarketDataKey}.</li>
 *   <li><b>No credit spread.</b> Every cashflow discounts at the risk-free rate, so a
 *       corporate bond prices as though it were a government one.</li>
 * </ul>
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class DiscountedCashflowModel<T extends FinancialInstrument & CashflowGenerating>
        implements PricingModel<T> {

    public static final ModelName NAME = ModelName.of("discounted-cashflow");

    /** Time is measured the same way here as in Black-Scholes, so the two agree. */
    private static final DayCountConvention TIME_CONVENTION = DayCountConvention.ACT_365F;

    private final Class<T> instrumentType;

    public DiscountedCashflowModel(Class<T> instrumentType) {
        this.instrumentType = Objects.requireNonNull(instrumentType, "instrumentType");
    }

    @Override
    public Class<T> instrumentType() {
        return instrumentType;
    }

    @Override
    public ModelName name() {
        return NAME;
    }

    @Override
    public ValuationResult price(T instrument, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        Currency target = instrument.currency();
        double presentValue = 0.0;

        for (Cashflow cashflow : instrument.cashflows(asOf)) {
            presentValue += presentValueOf(cashflow, market, asOf, target);
        }
        return new ValuationResult(presentValue, target, NAME);
    }

    /**
     * One cashflow, discounted on its own currency's curve and converted to {@code target}.
     *
     * <p>Discounting before converting is the correct order and not merely a preference: the
     * cashflow is certain in <em>its</em> currency, so it is that currency's time value which
     * applies. Converting first and discounting at the target rate would price a euro payment
     * using dollar interest rates.
     */
    private double presentValueOf(Cashflow cashflow, MarketDataSnapshot market,
                                  LocalDate asOf, Currency target) {
        Currency currency = cashflow.amount().currency();
        double years = TIME_CONVENTION.yearFraction(asOf, cashflow.paymentDate());
        double discountFactor = Math.exp(-market.discountRate(currency) * years);
        double amount = cashflow.amount().amount().doubleValue();

        return amount * discountFactor * market.fxRate(currency, target);
    }
}

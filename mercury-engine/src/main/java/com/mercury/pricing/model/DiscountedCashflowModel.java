package com.mercury.pricing.model;

import com.mercury.core.money.Currency;
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
 * <p><b>M6 settled this.</b> A floating swap leg was the predicted second case, the one whose
 * coupons must be projected off a curve before they can be discounted - and it arrived. What
 * it justified was extracting {@link CashflowDiscounting}, a four-line static function that
 * this model and {@link SwapModel} both call. Not a base class: there is still nothing for a
 * subclass to override, only a sum two pricers need to compute identically. Waiting for the
 * second case did not vindicate the pattern the design predicted; it showed the pattern was
 * never the right shape.
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
 * <p>{@code T} is measured by the curve, which uses ACT/365F - the same convention
 * {@link BlackScholesModel} uses - so an option and a bond in one portfolio agree about how
 * long a year is.
 *
 * <h2>Simplifications, named</h2>
 * <ul>
 *   <li><b>Continuous compounding.</b> {@code e^-rT} rather than {@code (1+r)^-T}. The two
 *       differ by roughly {@code r^2 T / 2}, so quoting a rate on the wrong basis is a real
 *       error - the convention is fixed here and stated on {@code MarketDataKey}.</li>
 *   <li><b>No credit spread.</b> Every cashflow discounts at the risk-free rate, so a
 *       corporate bond prices as though it were a government one.</li>
 * </ul>
 *
 * <p>Flat discounting was on this list until M5b. Each cashflow now discounts on the actual
 * term structure of its currency, so a ten-year payment no longer discounts at the overnight
 * rate. Nothing here changed to make that work: the model asks the snapshot for a curve, and a
 * market quoted as a single flat rate returns a one-pillar curve that discounts exactly as the
 * old code did.
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class DiscountedCashflowModel<T extends FinancialInstrument & CashflowGenerating>
        implements PricingModel<T> {

    public static final ModelName NAME = ModelName.of("discounted-cashflow");

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
        double presentValue = CashflowDiscounting.presentValue(
                instrument.cashflows(asOf), market, target);

        return new ValuationResult(presentValue, target, NAME);
    }
}

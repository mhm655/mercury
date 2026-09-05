package com.mercury.core.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for {@link Money}.
 *
 * <p>Examples show that a function is right for the cases someone thought of. Properties
 * state what must hold for <em>every</em> input, and jqwik then attacks them with values
 * nobody thought of - zero, huge magnitudes, awkward halves - and shrinks any failure to
 * a minimal counterexample. For money arithmetic, where the interesting bugs live in
 * rounding and accumulation rather than in the happy path, that is the more useful tool.
 */
class MoneyProperties {

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of(Currency.values());
    }

    /** Amounts within a range that stays realistic for a trading book. */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs()
                .between(-1_000_000_000L, 1_000_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Property
    void additionIsCommutative(@ForAll("amounts") BigDecimal a,
                               @ForAll("amounts") BigDecimal b,
                               @ForAll("currencies") Currency currency) {
        Money left = Money.of(a, currency);
        Money right = Money.of(b, currency);

        assertThat(left.plus(right)).isEqualTo(right.plus(left));
    }

    @Property
    void additionIsAssociative(@ForAll("amounts") BigDecimal a,
                               @ForAll("amounts") BigDecimal b,
                               @ForAll("amounts") BigDecimal c,
                               @ForAll("currencies") Currency currency) {
        Money x = Money.of(a, currency);
        Money y = Money.of(b, currency);
        Money z = Money.of(c, currency);

        assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
    }

    @Property
    void zeroIsTheAdditiveIdentity(@ForAll("amounts") BigDecimal a,
                                   @ForAll("currencies") Currency currency) {
        Money money = Money.of(a, currency);

        assertThat(money.plus(Money.zero(currency))).isEqualTo(money);
    }

    @Property
    void subtractionUndoesAddition(@ForAll("amounts") BigDecimal a,
                                   @ForAll("amounts") BigDecimal b,
                                   @ForAll("currencies") Currency currency) {
        Money left = Money.of(a, currency);
        Money right = Money.of(b, currency);

        assertThat(left.plus(right).minus(right)).isEqualTo(left);
    }

    @Property
    void negationIsItsOwnInverse(@ForAll("amounts") BigDecimal a,
                                 @ForAll("currencies") Currency currency) {
        Money money = Money.of(a, currency);

        assertThat(money.negated().negated()).isEqualTo(money);
        assertThat(money.plus(money.negated()).isZero()).isTrue();
    }

    @Property
    void absoluteValueIsNeverNegative(@ForAll("amounts") BigDecimal a,
                                      @ForAll("currencies") Currency currency) {
        assertThat(Money.of(a, currency).abs().isNegative()).isFalse();
    }

    /**
     * The property that most justifies {@code BigDecimal}: adding a fixed amount n times
     * must equal multiplying it by n exactly, with no drift however large n grows. The
     * same loop in {@code double} fails this for most amounts.
     */
    @Property
    void repeatedAdditionEqualsMultiplication(@ForAll @IntRange(min = 0, max = 500) int times,
                                              @ForAll("currencies") Currency currency) {
        Money unit = Money.ofMinor(1, currency);

        Money accumulated = Money.zero(currency);
        for (int i = 0; i < times; i++) {
            accumulated = accumulated.plus(unit);
        }

        assertThat(accumulated).isEqualTo(unit.multipliedBy((long) times));
    }

    @Property
    void comparisonIsConsistentWithEquality(@ForAll("amounts") BigDecimal a,
                                            @ForAll("amounts") BigDecimal b,
                                            @ForAll("currencies") Currency currency) {
        Money left = Money.of(a, currency);
        Money right = Money.of(b, currency);

        boolean comparesEqual = left.compareTo(right) == 0;

        // Because scale is normalised on construction, compareTo == 0 and equals agree.
        // Raw BigDecimal does not have this property, which is why normalisation matters.
        assertThat(comparesEqual).isEqualTo(left.equals(right));
    }

    @Property
    void orderingIsAntisymmetric(@ForAll("amounts") BigDecimal a,
                                 @ForAll("amounts") BigDecimal b,
                                 @ForAll("currencies") Currency currency) {
        Money left = Money.of(a, currency);
        Money right = Money.of(b, currency);

        assertThat(Integer.signum(left.compareTo(right)))
                .isEqualTo(-Integer.signum(right.compareTo(left)));
    }

    @Property
    void constructionAlwaysNormalisesToCurrencyScale(@ForAll("amounts") BigDecimal a,
                                                     @ForAll("currencies") Currency currency) {
        assertThat(Money.of(a, currency).amount().scale()).isEqualTo(currency.minorUnits());
    }

    @Property
    void sumOfPartsEqualsSumOfWhole(@ForAll("currencies") Currency currency,
                                    @ForAll @IntRange(min = 1, max = 50) int count) {
        // A portfolio's total is the sum of its positions however the positions are grouped.
        List<Money> parts = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> Money.ofMinor(i, currency))
                .toList();

        Money summedForwards = parts.stream().reduce(Money.zero(currency), Money::plus);
        Money summedBackwards = parts.reversed().stream().reduce(Money.zero(currency), Money::plus);

        assertThat(summedForwards).isEqualTo(summedBackwards);
        assertThat(summedForwards).isEqualTo(Money.ofMinor((long) count * (count + 1) / 2, currency));
    }
}

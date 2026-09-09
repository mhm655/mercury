package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three methods against the worked example that motivates having three.
 *
 * <p>Buy 100 at 50, buy 100 more at 90, sell 100 at 100. The answer is 5,000 under FIFO, 1,000
 * under LIFO and 3,000 under average cost - same trades, same market, three different profits,
 * all correct. If a test suite for this class asserted only one number it would be testing an
 * implementation rather than a policy.
 */
class CostBasisMethodTest {

    private static final LocalDate JANUARY = LocalDate.of(2024, 1, 15);
    private static final LocalDate JUNE = LocalDate.of(2024, 6, 15);

    /** 100 bought cheap in January, 100 bought dear in June. */
    private static List<Lot> twoLots() {
        return List.of(
                new Lot(Quantity.of(100), Money.of("5000", Currency.USD), JANUARY),
                new Lot(Quantity.of(100), Money.of("9000", Currency.USD), JUNE));
    }

    @Nested
    @DisplayName("the worked example")
    class WorkedExample {

        @Test
        @DisplayName("FIFO sells the January lot")
        void fifo() {
            CostBasisMethod.Consumed consumed = CostBasisMethod.FIRST_IN_FIRST_OUT
                    .consume(twoLots(), Quantity.of(100));

            assertThat(consumed.cost()).isEqualTo(Money.of("5000.00", Currency.USD));
            assertThat(consumed.remaining()).hasSize(1);
            assertThat(consumed.remaining().get(0).acquired()).isEqualTo(JUNE);
        }

        @Test
        @DisplayName("LIFO sells the June lot")
        void lifo() {
            CostBasisMethod.Consumed consumed = CostBasisMethod.LAST_IN_FIRST_OUT
                    .consume(twoLots(), Quantity.of(100));

            assertThat(consumed.cost()).isEqualTo(Money.of("9000.00", Currency.USD));
            assertThat(consumed.remaining()).hasSize(1);
            assertThat(consumed.remaining().get(0).acquired()).isEqualTo(JANUARY);
        }

        @Test
        @DisplayName("average cost sells half of everything")
        void average() {
            CostBasisMethod.Consumed consumed = CostBasisMethod.AVERAGE_COST
                    .consume(twoLots(), Quantity.of(100));

            assertThat(consumed.cost()).isEqualTo(Money.of("7000.00", Currency.USD));
            // Averaging deliberately forgets which purchase was which - that is what the
            // method means, not an omission.
            assertThat(consumed.remaining()).hasSize(1);
            assertThat(consumed.remaining().get(0).quantity()).isEqualTo(Quantity.of(100));
        }

        @Test
        @DisplayName("the three disagree, which is the whole reason the choice exists")
        void theyDiffer() {
            Money fifo = CostBasisMethod.FIRST_IN_FIRST_OUT
                    .consume(twoLots(), Quantity.of(100)).cost();
            Money lifo = CostBasisMethod.LAST_IN_FIRST_OUT
                    .consume(twoLots(), Quantity.of(100)).cost();
            Money average = CostBasisMethod.AVERAGE_COST
                    .consume(twoLots(), Quantity.of(100)).cost();

            assertThat(fifo).isNotEqualTo(lifo).isNotEqualTo(average);
            assertThat(average).isBetween(fifo, lifo);
        }
    }

    @Nested
    @DisplayName("cost is conserved")
    class Conservation {

        @Test
        @DisplayName("a partial disposal splits a lot without creating or losing a cent")
        void partialDisposalConservesCost() {
            // Selling 150 takes the whole January lot and half of June's. Whatever rounding the
            // split needs, the part taken and the part left must still add to 14,000 - a method
            // that rounded both halves independently would leak a cent per disposal.
            for (CostBasisMethod method : CostBasisMethod.values()) {
                CostBasisMethod.Consumed consumed = method.consume(twoLots(), Quantity.of(150));

                Money remaining = consumed.remaining().stream()
                        .map(Lot::cost)
                        .reduce(Money.zero(Currency.USD), Money::plus);

                assertThat(consumed.cost().plus(remaining))
                        .as("cost conserved under %s", method.displayName())
                        .isEqualTo(Money.of("14000.00", Currency.USD));
            }
        }

        @Test
        @DisplayName("quantity is conserved too")
        void quantityIsConserved() {
            for (CostBasisMethod method : CostBasisMethod.values()) {
                CostBasisMethod.Consumed consumed = method.consume(twoLots(), Quantity.of(150));

                Quantity remaining = consumed.remaining().stream()
                        .map(Lot::quantity)
                        .reduce(Quantity.ZERO, Quantity::plus);

                assertThat(remaining)
                        .as("quantity remaining under %s", method.displayName())
                        .isEqualTo(Quantity.of(50));
            }
        }

        @Test
        @DisplayName("consuming everything leaves nothing behind")
        void fullDisposal() {
            for (CostBasisMethod method : CostBasisMethod.values()) {
                CostBasisMethod.Consumed consumed = method.consume(twoLots(), Quantity.of(200));

                assertThat(consumed.cost()).isEqualTo(Money.of("14000.00", Currency.USD));
                assertThat(consumed.remaining()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("short positions")
    class Shorts {

        /** Sold short at 100, then at 80: cost is negative, because the cash came in. */
        private static List<Lot> shortLots() {
            return List.of(
                    new Lot(Quantity.of(-100), Money.of("-10000", Currency.USD), JANUARY),
                    new Lot(Quantity.of(-100), Money.of("-8000", Currency.USD), JUNE));
        }

        @Test
        @DisplayName("the same ordering applies, with the signs carried through")
        void shortsConsumeInTheSameOrder() {
            assertThat(CostBasisMethod.FIRST_IN_FIRST_OUT
                    .consume(shortLots(), Quantity.of(-100)).cost())
                    .isEqualTo(Money.of("-10000.00", Currency.USD));
            assertThat(CostBasisMethod.LAST_IN_FIRST_OUT
                    .consume(shortLots(), Quantity.of(-100)).cost())
                    .isEqualTo(Money.of("-8000.00", Currency.USD));
        }

        @Test
        @DisplayName("a partially covered short leaves a short remainder, not a long one")
        void partialCoverStaysShort() {
            // The sign bookkeeping is the part of this that is easy to get wrong, and getting
            // it wrong turns a half-covered short into a long position of the same size.
            CostBasisMethod.Consumed consumed = CostBasisMethod.FIRST_IN_FIRST_OUT
                    .consume(shortLots(), Quantity.of(-150));

            Quantity remaining = consumed.remaining().stream()
                    .map(Lot::quantity)
                    .reduce(Quantity.ZERO, Quantity::plus);

            assertThat(remaining).isEqualTo(Quantity.of(-50));
            assertThat(remaining.isShort()).isTrue();
        }
    }
}

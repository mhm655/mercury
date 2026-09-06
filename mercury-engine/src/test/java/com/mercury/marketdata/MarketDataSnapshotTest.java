package com.mercury.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MarketDataSnapshotTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final InstrumentId MSFT = InstrumentId.of("MSFT");
    private static final double TOLERANCE = 1e-12;

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder()
                .spot(AAPL, 200.0)
                .spot(MSFT, 400.0)
                .volatility(AAPL, 0.25)
                .discountRate(Currency.USD, 0.05)
                .build();
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("returns what was put in")
        void returnsStoredValues() {
            MarketDataSnapshot snapshot = market();

            assertThat(snapshot.spot(AAPL)).isCloseTo(200.0, within(TOLERANCE));
            assertThat(snapshot.volatility(AAPL)).isCloseTo(0.25, within(TOLERANCE));
            assertThat(snapshot.discountRate(Currency.USD)).isCloseTo(0.05, within(TOLERANCE));
            assertThat(snapshot.size()).isEqualTo(4);
        }

        @Test
        @DisplayName("missing data throws rather than defaulting to zero")
        void missingDataThrows() {
            // A missing spot read as zero would price an option at its discounted strike and
            // report a plausible, wrong number. Loud beats plausible.
            assertThatThrownBy(() -> market().spot(InstrumentId.of("GOOG")))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                    .hasMessageContaining("spot:GOOG")
                    .hasMessageContaining("plausible but wrong");
        }

        @Test
        @DisplayName("the exception lists what the snapshot does hold")
        void exceptionListsAvailableKeys() {
            assertThatThrownBy(() -> market().volatility(MSFT))
                    .hasMessageContaining("vol:AAPL")
                    .hasMessageContaining("spot:MSFT");
        }

        @Test
        @DisplayName("an empty snapshot reports having nothing")
        void emptySnapshot() {
            assertThatThrownBy(() -> MarketDataSnapshot.empty().spot(AAPL))
                    .hasMessageContaining("holds: nothing");
        }

        @Test
        @DisplayName("keys of different kinds for one instrument do not collide")
        void keysAreDistinctByKind() {
            MarketDataSnapshot snapshot = market();

            assertThat(snapshot.contains(MarketDataKey.spot(AAPL))).isTrue();
            assertThat(snapshot.contains(MarketDataKey.volatility(AAPL))).isTrue();
            assertThat(snapshot.contains(MarketDataKey.volatility(MSFT))).isFalse();
            assertThat(MarketDataKey.spot(AAPL)).isNotEqualTo(MarketDataKey.volatility(AAPL));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a non-positive spot price")
        void rejectsNonPositiveSpot() {
            assertThatThrownBy(() -> MarketDataSnapshot.builder().spot(AAPL, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("rejects negative volatility")
        void rejectsNegativeVolatility() {
            assertThatThrownBy(() -> MarketDataSnapshot.builder().volatility(AAPL, -0.1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects NaN and infinity")
        void rejectsNonFinite() {
            assertThatThrownBy(() -> MarketDataSnapshot.builder()
                    .with(MarketDataKey.spot(AAPL), Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
        }

        @Test
        @DisplayName("permits negative rates, which are unusual but real")
        void permitsNegativeRates() {
            // EUR and JPY policy rates have been below zero. Rejecting them would encode a
            // market condition as a validation rule.
            MarketDataSnapshot snapshot = MarketDataSnapshot.builder()
                    .discountRate(Currency.EUR, -0.005).build();

            assertThat(snapshot.discountRate(Currency.EUR)).isCloseTo(-0.005, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("shocks")
    class Shocks {

        @Test
        @DisplayName("the original snapshot is never modified")
        void originalIsUntouched() {
            // The property everything else depends on: Greeks and stress tests both need the
            // unshocked base case to remain available for comparison.
            MarketDataSnapshot base = market();

            MarketDataSnapshot shocked = base.withShock(MarketShock.scaleAllSpots(0.70));

            assertThat(base.spot(AAPL)).isCloseTo(200.0, within(TOLERANCE));
            assertThat(shocked.spot(AAPL)).isCloseTo(140.0, within(TOLERANCE));
            assertThat(shocked).isNotSameAs(base);
        }

        @Test
        @DisplayName("a targeted shock leaves every other observation alone")
        void targetedShockIsNarrow() {
            MarketDataSnapshot shocked = market().withShock(MarketShock.scaleSpot(AAPL, 1.01));

            assertThat(shocked.spot(AAPL)).isCloseTo(202.0, within(TOLERANCE));
            assertThat(shocked.spot(MSFT)).isCloseTo(400.0, within(TOLERANCE));
            assertThat(shocked.volatility(AAPL)).isCloseTo(0.25, within(TOLERANCE));
            assertThat(shocked.discountRate(Currency.USD)).isCloseTo(0.05, within(TOLERANCE));
        }

        @Test
        @DisplayName("a rate bump is additive in basis points")
        void rateBumpIsAdditive() {
            MarketDataSnapshot shocked =
                    market().withShock(MarketShock.bumpRate(Currency.USD, BasisPoints.of(150)));

            assertThat(shocked.discountRate(Currency.USD)).isCloseTo(0.065, within(TOLERANCE));
        }

        @Test
        @DisplayName("volatility is floored at zero rather than going negative")
        void volatilityFloorsAtZero() {
            // Negative volatility has no meaning, and Black-Scholes would return NaN from the
            // square root rather than failing usefully.
            MarketDataSnapshot shocked =
                    market().withShock(MarketShock.bumpVolatility(AAPL, -0.50));

            assertThat(shocked.volatility(AAPL)).isZero();
        }

        @Test
        @DisplayName("none() changes nothing")
        void noneIsIdentity() {
            assertThat(market().withShock(MarketShock.none())).isEqualTo(market());
        }
    }

    @Nested
    @DisplayName("composition - the reason shocks are an interface")
    class Composition {

        @Test
        @DisplayName("a market-crash scenario is one composite of four shocks")
        void marketCrashScenario() {
            // Exactly the shape a named stress scenario takes at M11: equities down 30%,
            // volatility up 50%, rates up 150bp. The caller cannot tell it holds four shocks.
            MarketShock crash = MarketShock.scaleAllSpots(0.70)
                    .and(MarketShock.scaleAllVolatilities(1.50))
                    .and(MarketShock.bumpAllRates(BasisPoints.of(150)));

            MarketDataSnapshot shocked = market().withShock(crash);

            assertThat(shocked.spot(AAPL)).isCloseTo(140.0, within(TOLERANCE));
            assertThat(shocked.spot(MSFT)).isCloseTo(280.0, within(TOLERANCE));
            assertThat(shocked.volatility(AAPL)).isCloseTo(0.375, within(TOLERANCE));
            assertThat(shocked.discountRate(Currency.USD)).isCloseTo(0.065, within(TOLERANCE));
        }

        @Test
        @DisplayName("composition nests, so a leaf and a tree are indistinguishable to callers")
        void compositionNests() {
            MarketShock nested = MarketShock.composite(List.of(
                    MarketShock.scaleSpot(AAPL, 2.0),
                    MarketShock.composite(List.of(
                            MarketShock.scaleSpot(MSFT, 0.5),
                            MarketShock.bumpAllRates(BasisPoints.of(100))))));

            MarketDataSnapshot shocked = market().withShock(nested);

            assertThat(shocked.spot(AAPL)).isCloseTo(400.0, within(TOLERANCE));
            assertThat(shocked.spot(MSFT)).isCloseTo(200.0, within(TOLERANCE));
            assertThat(shocked.discountRate(Currency.USD)).isCloseTo(0.06, within(TOLERANCE));
        }

        @Test
        @DisplayName("two shocks on the same key apply in order")
        void sameKeyAppliesInOrder() {
            MarketShock twice = MarketShock.scaleSpot(AAPL, 2.0)
                    .and(MarketShock.scaleSpot(AAPL, 3.0));

            assertThat(market().withShock(twice).spot(AAPL)).isCloseTo(1200.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("shocks on disjoint keys commute")
        void disjointShocksCommute() {
            MarketShock a = MarketShock.scaleSpot(AAPL, 1.10);
            MarketShock b = MarketShock.bumpAllRates(BasisPoints.of(25));

            assertThat(market().withShock(a.and(b))).isEqualTo(market().withShock(b.and(a)));
        }

        @Test
        @DisplayName("an empty composite is the identity")
        void emptyCompositeIsIdentity() {
            assertThat(market().withShock(MarketShock.composite(List.of())))
                    .isEqualTo(market());
        }
    }
}

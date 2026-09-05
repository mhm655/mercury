package com.mercury.core.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DomainIdTest {

    /** Every identifier type, paired with its factory, so the shared rules are checked once each. */
    private static final Map<String, Function<String, DomainId>> FACTORIES = Map.of(
            "InstrumentId", InstrumentId::of,
            "OrderId", OrderId::of,
            "TradeId", TradeId::of,
            "PortfolioId", PortfolioId::of,
            "CounterpartyId", CounterpartyId::of);

    @Test
    @DisplayName("carries its value and prints it bare")
    void carriesValue() {
        assertThat(InstrumentId.of("AAPL").value()).isEqualTo("AAPL");
        assertThat(InstrumentId.of("AAPL")).hasToString("AAPL");
    }

    @Test
    @DisplayName("equal values of the same type are equal")
    void equalityByValue() {
        assertThat(InstrumentId.of("AAPL")).isEqualTo(InstrumentId.of("AAPL"));
        assertThat(InstrumentId.of("AAPL")).hasSameHashCodeAs(InstrumentId.of("AAPL"));
        assertThat(InstrumentId.of("AAPL")).isNotEqualTo(InstrumentId.of("MSFT"));
    }

    @Test
    @DisplayName("different id types are never equal, even holding identical text")
    void differentTypesNeverEqual() {
        // The whole point of separate types. With String ids these would be equal and
        // interchangeable, and passing one where the other belongs would compile.
        assertThat((Object) InstrumentId.of("X-1")).isNotEqualTo(OrderId.of("X-1"));
        assertThat((Object) TradeId.of("X-1")).isNotEqualTo(PortfolioId.of("X-1"));
    }

    @Test
    @DisplayName("all identifier types trim surrounding whitespace")
    void trimsWhitespace() {
        FACTORIES.forEach((name, factory) ->
                assertThat(factory.apply("  ABC  ").value()).as("%s", name).isEqualTo("ABC"));
    }

    @Test
    @DisplayName("all identifier types reject blank values")
    void rejectsBlank() {
        FACTORIES.forEach((name, factory) -> {
            assertThatThrownBy(() -> factory.apply("")).as("%s with empty text", name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");

            assertThatThrownBy(() -> factory.apply("   ")).as("%s with blank text", name)
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("all identifier types reject null")
    void rejectsNull() {
        FACTORIES.forEach((name, factory) ->
                assertThatThrownBy(() -> factory.apply(null)).as("%s", name)
                        .isInstanceOf(NullPointerException.class));
    }

    @Test
    @DisplayName("works as a map key, which is how positions are held")
    void usableAsMapKey() {
        Map<InstrumentId, String> positions = Map.of(
                InstrumentId.of("AAPL"), "long 100",
                InstrumentId.of("MSFT"), "short 50");

        assertThat(positions.get(InstrumentId.of("AAPL"))).isEqualTo("long 100");
        assertThat(positions).doesNotContainKey(InstrumentId.of("GOOG"));
    }

    @Test
    @DisplayName("the sealed hierarchy covers exactly the five identifier types")
    void sealedHierarchyIsComplete() {
        List<Class<?>> permitted = List.of(DomainId.class.getPermittedSubclasses());

        assertThat(permitted).containsExactlyInAnyOrder(
                InstrumentId.class, OrderId.class, TradeId.class,
                PortfolioId.class, CounterpartyId.class);
    }
}

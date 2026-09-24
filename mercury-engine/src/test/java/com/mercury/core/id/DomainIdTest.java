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
    @DisplayName("all identifier types reject text that could forge or disguise output")
    void rejectsHostileText() {
        // Ids are printed verbatim into the report, the TUI and every exception message, so:
        List<String> hostile = List.of(
                "AAPL\u001b[2J",          // an ANSI escape that clears the reader's terminal
                "AAPL\nTRD-9 FORGED",      // a newline that forges an extra line of output
                "AA\rPL",                  // a carriage return that overwrites the line
                "\u0391APL",               // Greek capital alpha: looks exactly like AAPL
                "AAPL\u202E",              // a bidi override that reverses what follows
                "AA\u200BPL",              // a zero-width space: two ids that print the same
                "A".repeat(65));           // unbounded length is a memory and layout attack
        FACTORIES.forEach((name, factory) -> hostile.forEach(text ->
                assertThatThrownBy(() -> factory.apply(text)).as("%s with %s", name, text)
                        .isInstanceOf(IllegalArgumentException.class)));
    }

    @Test
    @DisplayName("all identifier types accept the printable ASCII the engine uses")
    void acceptsOrdinaryIds() {
        FACTORIES.forEach((name, factory) -> {
            assertThat(factory.apply("AAPL-C-200").value()).as("%s", name).isEqualTo("AAPL-C-200");
            assertThat(factory.apply("US EQUITY_BOOK/2.1").value()).as("%s", name)
                    .isEqualTo("US EQUITY_BOOK/2.1");
            assertThat(factory.apply("A".repeat(64)).value()).as("%s", name).hasSize(64);
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

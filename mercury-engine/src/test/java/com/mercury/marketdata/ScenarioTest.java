package com.mercury.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final MarketDataKey AAPL_SPOT = MarketDataKey.spot(AAPL);

    @Test
    void nameAndDescriptionRoundTrip() {
        Scenario scenario = Scenario.builder("Market Crash")
                .description("equities -30%")
                .shock(MarketShock.scaleAllSpots(0.70))
                .build();

        assertThat(scenario.name()).isEqualTo("Market Crash");
        assertThat(scenario.description()).isEqualTo("equities -30%");
    }

    @Test
    void descriptionDefaultsToEmpty() {
        Scenario scenario = Scenario.builder("Unlabelled")
                .shock(MarketShock.scaleAllSpots(0.70))
                .build();

        assertThat(scenario.description()).isEmpty();
    }

    @Test
    void oneComponentComposesToItself() {
        MarketShock component = MarketShock.scaleAllSpots(0.70);
        Scenario scenario = Scenario.builder("Crash").shock(component).build();

        assertThat(scenario.shock().shockFor(AAPL_SPOT, 100.0))
                .isEqualTo(component.shockFor(AAPL_SPOT, 100.0));
    }

    @Test
    void multipleComponentsComposeInOrder() {
        // Each halves the value: 100 -> 50 -> 25, exactly what MarketShock.composite already
        // guarantees - this asserts the Builder actually delegates to it rather than
        // reimplementing composition.
        Scenario scenario = Scenario.builder("Double halving")
                .shock(MarketShock.scaleAllSpots(0.5))
                .shock(MarketShock.scaleAllSpots(0.5))
                .build();

        assertThat(scenario.shock().shockFor(AAPL_SPOT, 100.0)).isEqualTo(25.0);
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Scenario.builder(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBuildingWithNoShocks() {
        assertThatThrownBy(() -> Scenario.builder("Empty").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no shocks");
    }

    @Test
    void toStringNamesAndDescribesIt() {
        Scenario scenario = Scenario.builder("Market Crash")
                .description("equities -30%")
                .shock(MarketShock.scaleAllSpots(0.70))
                .build();

        assertThat(scenario.toString()).isEqualTo("Market Crash (equities -30%)");
    }

    @Test
    void toStringOmitsEmptyParensWhenThereIsNoDescription() {
        // A blank description is a supported path (descriptionDefaultsToEmpty above), so
        // toString() must not print a dangling "Name ()" for it.
        Scenario scenario = Scenario.builder("Unlabelled")
                .shock(MarketShock.scaleAllSpots(0.70))
                .build();

        assertThat(scenario.toString()).isEqualTo("Unlabelled");
    }
}

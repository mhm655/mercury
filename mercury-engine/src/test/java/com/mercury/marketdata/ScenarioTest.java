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
        Scenario scenario = Scenario.of("Market Crash", "equities -30%",
                MarketShock.scaleAllSpots(0.70));

        assertThat(scenario.name()).isEqualTo("Market Crash");
        assertThat(scenario.description()).isEqualTo("equities -30%");
    }

    @Test
    void descriptionDefaultsToEmptyWhenGivenAnEmptyString() {
        Scenario scenario = Scenario.of("Unlabelled", "", MarketShock.scaleAllSpots(0.70));

        assertThat(scenario.description()).isEmpty();
    }

    @Test
    void oneComponentComposesToItself() {
        MarketShock component = MarketShock.scaleAllSpots(0.70);
        Scenario scenario = Scenario.of("Crash", "", component);

        assertThat(scenario.shock().shockFor(AAPL_SPOT, 100.0))
                .isEqualTo(component.shockFor(AAPL_SPOT, 100.0));
    }

    @Test
    void multipleComponentsComposeInOrder() {
        // Each halves the value: 100 -> 50 -> 25, exactly what MarketShock.composite already
        // guarantees - this asserts the factory actually delegates to it rather than
        // reimplementing composition.
        Scenario scenario = Scenario.of("Double halving", "",
                MarketShock.scaleAllSpots(0.5), MarketShock.scaleAllSpots(0.5));

        assertThat(scenario.shock().shockFor(AAPL_SPOT, 100.0)).isEqualTo(25.0);
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Scenario.of(" ", "", MarketShock.scaleAllSpots(0.70)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsNoShocks() {
        assertThatThrownBy(() -> Scenario.of("Empty", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no shocks");
    }

    @Test
    void rejectsANullShockAmongOthers() {
        assertThatThrownBy(() -> Scenario.of("Bad", "", MarketShock.scaleAllSpots(0.70), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringNamesAndDescribesIt() {
        Scenario scenario = Scenario.of("Market Crash", "equities -30%",
                MarketShock.scaleAllSpots(0.70));

        assertThat(scenario.toString()).isEqualTo("Market Crash (equities -30%)");
    }

    @Test
    void toStringOmitsEmptyParensWhenThereIsNoDescription() {
        // A blank description is a supported path (descriptionDefaultsToEmptyWhenGivenAnEmptyString
        // above), so toString() must not print a dangling "Name ()" for it.
        Scenario scenario = Scenario.of("Unlabelled", "", MarketShock.scaleAllSpots(0.70));

        assertThat(scenario.toString()).isEqualTo("Unlabelled");
    }
}

package com.mercury.app.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * What M16a actually needs proven, with no terminal involved: steps run in declaration
 * order, each action runs exactly once, and advancing past the end fails loudly rather
 * than doing nothing.
 */
class ScenarioStepperTest {

    @Test
    void stepsRunInOrderExactlyOnce() {
        List<String> ran = new ArrayList<>();
        ScenarioStepper stepper = new ScenarioStepper(List.of(
                new ScenarioStep("first", () -> ran.add("first")),
                new ScenarioStep("second", () -> ran.add("second")),
                new ScenarioStep("third", () -> ran.add("third"))));

        stepper.advance();
        stepper.advance();
        stepper.advance();

        assertThat(ran).containsExactly("first", "second", "third");
    }

    @Test
    void peekDoesNotRunTheAction() {
        List<String> ran = new ArrayList<>();
        ScenarioStepper stepper = new ScenarioStepper(
                List.of(new ScenarioStep("only", () -> ran.add("only"))));

        assertThat(stepper.peek().label()).isEqualTo("only");
        assertThat(stepper.peek().label()).isEqualTo("only");
        assertThat(ran).isEmpty();

        stepper.advance();
        assertThat(ran).containsExactly("only");
    }

    @Test
    void hasNextIsFalseOnceEveryStepHasRun() {
        ScenarioStepper stepper = new ScenarioStepper(
                List.of(new ScenarioStep("only", () -> {
                })));

        assertThat(stepper.hasNext()).isTrue();
        stepper.advance();
        assertThat(stepper.hasNext()).isFalse();
    }

    @Test
    void advancingPastTheLastStepThrows() {
        ScenarioStepper stepper = new ScenarioStepper(
                List.of(new ScenarioStep("only", () -> {
                })));
        stepper.advance();

        assertThatThrownBy(stepper::advance).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(stepper::peek).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void stepsRunAndStepCountTrackProgress() {
        ScenarioStepper stepper = new ScenarioStepper(List.of(
                new ScenarioStep("a", () -> {
                }),
                new ScenarioStep("b", () -> {
                })));

        assertThat(stepper.stepCount()).isEqualTo(2);
        assertThat(stepper.stepsRun()).isEqualTo(0);

        stepper.advance();
        assertThat(stepper.stepsRun()).isEqualTo(1);
    }
}

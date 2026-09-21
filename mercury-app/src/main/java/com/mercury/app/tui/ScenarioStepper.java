package com.mercury.app.tui;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Steps through a fixed sequence of {@link ScenarioStep}s one at a time, running each
 * step's action only when the caller advances past it - never ahead of the caller, and
 * never twice.
 *
 * <p>Separated from any notion of a terminal so it is testable without one: what M16a
 * needs proven is that steps run in order, exactly once each, and that advancing past the
 * last one fails rather than silently doing nothing. How a step is announced or rendered
 * is the caller's job - {@code TuiDemo} today, a redrawing panel from M16b on.
 */
public final class ScenarioStepper {

    private final List<ScenarioStep> steps;
    private int nextIndex = 0;

    public ScenarioStepper(List<ScenarioStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public boolean hasNext() {
        return nextIndex < steps.size();
    }

    /** The step {@link #advance()} would run next, without running it. */
    public ScenarioStep peek() {
        if (!hasNext()) {
            throw new NoSuchElementException("no steps remain");
        }
        return steps.get(nextIndex);
    }

    /** Runs the next step's action and returns the step that ran. */
    public ScenarioStep advance() {
        ScenarioStep step = peek();
        step.action().run();
        nextIndex++;
        return step;
    }

    /** How many steps have run so far. */
    public int stepsRun() {
        return nextIndex;
    }

    public int stepCount() {
        return steps.size();
    }
}

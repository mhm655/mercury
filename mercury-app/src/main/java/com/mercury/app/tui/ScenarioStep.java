package com.mercury.app.tui;

/**
 * One economic event in a replayed scenario: a label to show before it runs, and the
 * instruction that runs it.
 */
public record ScenarioStep(String label, Runnable action) {
}

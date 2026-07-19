package id.behavio.bank.platform.core.rule;

/**
 * Satu aturan: bila {@code when} cocok → jalankan {@code then}.
 * Dievaluasi berurutan dalam Scenario (FIRST-MATCH).
 */
public record Rule(
        String name,
        Condition when,
        Outcome then
) {}

package id.behavio.core.rule;

import java.util.List;

/**
 * Bagian THEN dari Rule (dan fallback Scenario): aksi state + response.
 * Fault & webhook (design.md §8.2) menyusul di Fase 2 — di sini disediakan
 * response + actions untuk vertical slice Fase 1.
 */
public record Outcome(
        List<Action> actions,
        ResponseSpec response
) {
    public static Outcome of(ResponseSpec response) {
        return new Outcome(List.of(), response);
    }
}

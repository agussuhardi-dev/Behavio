package id.behavio.core.rule;

import java.util.List;

/**
 * Bagian THEN dari Rule (dan fallback Scenario): aksi state + response + fault + webhook
 * (opsional). (design.md §8.2). Field null berarti bagian itu tidak dipakai.
 */
public record Outcome(
        List<Action> actions,
        ResponseSpec response,
        FaultSpec fault,
        WebhookSpec webhook
) {
    /** Tanpa fault/webhook (alur normal). */
    public Outcome(List<Action> actions, ResponseSpec response) {
        this(actions, response, null, null);
    }

    public Outcome(List<Action> actions, ResponseSpec response, FaultSpec fault) {
        this(actions, response, fault, null);
    }

    public static Outcome of(ResponseSpec response) {
        return new Outcome(List.of(), response, null, null);
    }

    public static Outcome of(List<Action> actions, ResponseSpec response) {
        return new Outcome(actions, response, null, null);
    }

    public static Outcome withFault(List<Action> actions, ResponseSpec response, FaultSpec fault) {
        return new Outcome(actions, response, fault, null);
    }

    public static Outcome withWebhook(List<Action> actions, ResponseSpec response, WebhookSpec webhook) {
        return new Outcome(actions, response, null, webhook);
    }
}

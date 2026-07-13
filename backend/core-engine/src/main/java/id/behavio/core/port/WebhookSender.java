package id.behavio.core.port;

import java.time.Duration;
import java.util.Map;

/**
 * Outbound port: penjadwalan webhook async (persistent outbox + retry).
 * Adapter-webhook menyimpan ke outbox lalu worker mengirim.
 */
public interface WebhookSender {

    void schedule(String url, Map<String, String> headers, String body, Duration delay);
}

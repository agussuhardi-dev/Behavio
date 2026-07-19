package id.behavio.qris.platform.core.port;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port: penjadwalan webhook async (persistent outbox + retry).
 * Implementasi menyimpan ke outbox (dalam transaksi request) lalu worker mengirim.
 */
public interface WebhookSender {

    void schedule(UUID simulatorId, String url, Map<String, String> headers, String body, Duration delay);
}

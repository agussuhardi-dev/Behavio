package id.behavio.bank.platform.webhook;

import id.behavio.bank.platform.core.port.WebhookSender;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * WebhookSender berbasis outbox (design.md §9): meng-enqueue webhook ke tabel
 * {@code <schema>.webhook_outbox} DALAM transaksi request (atomik dengan perubahan
 * state). Pengiriman + retry dilakukan {@link WebhookWorker}.
 *
 * Satu instance per produk: webhook bank masuk outbox bank, webhook QRIS masuk outbox
 * QRIS (design.md §3.4).
 */
public class OutboxWebhookSender implements WebhookSender {

    private final JdbcClient db;
    private final String schema;

    /** @param schema schema produk pemilik outbox ini ("bank" / "qris") */
    public OutboxWebhookSender(JdbcClient db, String schema) {
        this.db = db;
        this.schema = schema;
    }

    @Override
    public void schedule(UUID simulatorId, String url, Map<String, String> headers, String body, Duration delay) {
        Instant dueAt = Instant.now().plus(delay == null ? Duration.ZERO : delay);
        db.sql("INSERT INTO " + schema + ".webhook_outbox "
                + "(id, simulator_id, url, headers, body, status, attempts, max_attempts, next_attempt_at) "
                + "VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING', 0, 5, ?)")
                .param(UUID.randomUUID()).param(simulatorId).param(url)
                .param(toJson(headers)).param(body)
                .param(java.sql.Timestamp.from(dueAt))
                .update();
    }

    private static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

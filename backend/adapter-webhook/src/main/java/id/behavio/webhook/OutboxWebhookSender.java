package id.behavio.webhook;

import id.behavio.core.port.WebhookSender;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * WebhookSender berbasis outbox (design.md §9): meng-enqueue webhook ke tabel
 * webhook_outbox DALAM transaksi request (atomik dengan perubahan state). Pengiriman
 * + retry dilakukan {@link WebhookWorker}.
 */
@Component
public class OutboxWebhookSender implements WebhookSender {

    private final JdbcClient db;

    public OutboxWebhookSender(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void schedule(UUID simulatorId, String url, Map<String, String> headers, String body, Duration delay) {
        Instant dueAt = Instant.now().plus(delay == null ? Duration.ZERO : delay);
        db.sql("""
                INSERT INTO webhook_outbox
                  (id, simulator_id, url, headers, body, status, attempts, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING', 0, 5, ?)
                """)
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

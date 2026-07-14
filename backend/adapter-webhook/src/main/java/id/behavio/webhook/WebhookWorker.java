package id.behavio.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Worker outbox webhook (design.md §9): polling tabel webhook_outbox, kirim via HTTP,
 * retry dengan backoff linear, tandai SENT/FAILED. Tahan restart (state di DB).
 */
@Component
public class WebhookWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final JdbcClient db;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebhookWorker(JdbcClient db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        List<Job> due = db.sql("""
                SELECT id, url, body, attempts, max_attempts FROM webhook_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY next_attempt_at LIMIT 20
                """)
                .query((rs, n) -> new Job(
                        rs.getObject("id", UUID.class), rs.getString("url"), rs.getString("body"),
                        rs.getInt("attempts"), rs.getInt("max_attempts")))
                .list();
        for (Job j : due) {
            send(j);
        }
    }

    private void send(Job j) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(j.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(j.body() == null ? "" : j.body()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                db.sql("UPDATE webhook_outbox SET status='SENT', attempts=?, sent_at=now(), last_error=NULL WHERE id=?")
                        .param(j.attempts() + 1).param(j.id()).update();
                log.info("[webhook] terkirim id={} → {} (HTTP {})", j.id(), j.url(), res.statusCode());
            } else {
                fail(j, "HTTP " + res.statusCode());
            }
        } catch (Exception e) {
            fail(j, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void fail(Job j, String error) {
        int attempts = j.attempts() + 1;
        if (attempts >= j.maxAttempts()) {
            db.sql("UPDATE webhook_outbox SET status='FAILED', attempts=?, last_error=? WHERE id=?")
                    .param(attempts).param(error).param(j.id()).update();
            log.warn("[webhook] GAGAL permanen id={} ({} percobaan): {}", j.id(), attempts, error);
        } else {
            Instant next = Instant.now().plusSeconds(attempts * 3L); // backoff linear
            db.sql("UPDATE webhook_outbox SET attempts=?, next_attempt_at=?, last_error=? WHERE id=?")
                    .param(attempts).param(Timestamp.from(next)).param(error).param(j.id()).update();
            log.info("[webhook] retry id={} percobaan={} error={}", j.id(), attempts, error);
        }
    }

    private record Job(UUID id, String url, String body, int attempts, int maxAttempts) {}
}

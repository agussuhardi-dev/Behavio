package id.behavio.web.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: kelola webhook subscription (registrasi URL callback) & pantau outbox.
 *
 * Subscription = URL callback yang TERDAFTAR (berbeda dari X-CALLBACK-URL per-request —
 * subscription ini berlaku untuk SEMUA request partner pada simulator).
 * Saat event terjadi (transfer sukses, VA dibayar), simulator mengirim webhook ke
 * subscription URL yang sesuai.
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/webhooks")
public class WebhookAdminController {

    private final JdbcClient db;

    public WebhookAdminController(JdbcClient db) {
        this.db = db;
    }

    // ---- subscription ----

    /** Daftar semua subscription untuk simulator ini. */
    @GetMapping("/subscriptions")
    public List<Map<String, Object>> listSubscriptions(@PathVariable UUID id) {
        return db.sql("""
                SELECT ws.id, ws.partner_id, p.partner_id as partner_label,
                       ws.url, ws.event_type, ws.status, ws.created_at
                FROM webhook_subscriptions ws
                JOIN partners p ON p.id = ws.partner_id AND p.simulator_id = ws.simulator_id
                WHERE ws.simulator_id = ?
                ORDER BY ws.created_at DESC
                """)
                .param(id)
                .query().listOfRows();
    }

    /** Registrasi URL callback baru. */
    @PostMapping("/subscriptions")
    public ResponseEntity<?> addSubscription(@PathVariable UUID id,
                                              @RequestBody Map<String, String> body) {
        String url = body.get("url");
        String eventType = body.getOrDefault("eventType", "ALL");
        String partnerId = body.get("partnerId");
        if (url == null || url.isBlank() || partnerId == null || partnerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'url' dan 'partnerId' wajib"));
        }

        UUID partnerUuid = db.sql("SELECT id FROM partners WHERE simulator_id = ? AND partner_id = ?")
                .param(id).param(partnerId)
                .query(UUID.class).optional().orElse(null);
        if (partnerUuid == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Partner '" + partnerId + "' tidak ditemukan"));
        }

        UUID subId = UUID.randomUUID();
        db.sql("""
                INSERT INTO webhook_subscriptions (id, simulator_id, partner_id, url, event_type, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """)
                .param(subId).param(id).param(partnerUuid).param(url).param(eventType)
                .update();
        return ResponseEntity.ok(Map.of("id", subId, "url", url, "status", "ACTIVE"));
    }

    /** Nonaktifkan subscription. */
    @PostMapping("/subscriptions/{subscriptionId}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        db.sql("UPDATE webhook_subscriptions SET status = 'INACTIVE' WHERE id = ? AND simulator_id = ?")
                .param(subscriptionId).param(id).update();
        return ResponseEntity.ok(Map.of("status", "INACTIVE"));
    }

    /** Aktifkan kembali subscription. */
    @PostMapping("/subscriptions/{subscriptionId}/activate")
    public ResponseEntity<?> activate(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        db.sql("UPDATE webhook_subscriptions SET status = 'ACTIVE' WHERE id = ? AND simulator_id = ?")
                .param(subscriptionId).param(id).update();
        return ResponseEntity.ok(Map.of("status", "ACTIVE"));
    }

    /** Hapus subscription. */
    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<?> deleteSubscription(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        db.sql("DELETE FROM webhook_subscriptions WHERE id = ? AND simulator_id = ?")
                .param(subscriptionId).param(id).update();
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // ---- outbox monitoring ----

    /** Pantau outbox: semua webhook yang pernah dijadwalkan (PENDING/SENT/FAILED). */
    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "20") int limit) {
        return db.sql("""
                SELECT id, url, body, status, attempts, max_attempts,
                       next_attempt_at, last_error, created_at, sent_at
                FROM webhook_outbox
                WHERE simulator_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """)
                .param(id).param(Math.min(100, limit))
                .query().listOfRows();
    }

    /** Retry manual: ubah status FAILED → PENDING agar worker mengirim ulang. */
    @PostMapping("/outbox/{outboxId}/retry")
    public ResponseEntity<?> retry(@PathVariable UUID id, @PathVariable UUID outboxId) {
        int updated = db.sql("""
                UPDATE webhook_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = NULL
                WHERE id = ? AND simulator_id = ? AND status = 'FAILED'
                """)
                .param(Timestamp.from(Instant.now())).param(outboxId).param(id)
                .update();
        if (updated == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Webhook tidak ditemukan atau tidak dalam status FAILED"));
        }
        return ResponseEntity.ok(Map.of("status", "retry-scheduled"));
    }
}

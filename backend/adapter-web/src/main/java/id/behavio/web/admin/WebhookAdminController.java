package id.behavio.web.admin;

import id.behavio.core.port.WebhookSubscriptions;
import id.behavio.web.ProductRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: pantau outbox webhook (design.md §9) — semua webhook yang pernah dijadwalkan
 * beserta status kirim/retry-nya, plus retry manual untuk yang FAILED.
 *
 * Generik lintas produk: tabel yang dibaca ditentukan segmen {@code {product}} di URL —
 * outbox bank ada di {@code bank.webhook_outbox}, outbox QRIS di
 * {@code qris.webhook_outbox}, dan keduanya tak pernah saling terlihat.
 *
 * <p><b>Riwayat subscription — dibuang 2026-07-14, dihidupkan 2026-07-15.</b> CRUD
 * subscription pernah ada di sini lalu dihapus, dengan alasan yang waktu itu benar: tak
 * ada satu pun kode engine yang membacanya saat mengirim, karena pengiriman nyata memakai
 * header {@code X-CALLBACK-URL} per-request. Ia kembali sekarang justru karena header itu
 * dibuang (design.md §9.1): registrasi kini <b>satu-satunya</b> sumber URL dan dibaca
 * setiap kali webhook dikirim, lewat port {@link id.behavio.core.port.WebhookSubscriptions}.
 *
 * <p>Ukuran yang sama tetap berlaku: kalau suatu hari registrasi ini tak lagi dibaca jalur
 * pengiriman, ia harus dihapus lagi — bukan dibiarkan jadi hiasan.
 */
@RestController
@RequestMapping("/api/admin/v1/{product}/simulators/{id}/webhooks")
public class WebhookAdminController {

    private final JdbcClient db;
    private final ProductRegistry products;

    public WebhookAdminController(JdbcClient db, ProductRegistry products) {
        this.db = db;
        this.products = products;
    }

    /**
     * Nama schema produk. Diambil lewat registry (yang menolak nilai tak dikenal dengan
     * 404), bukan langsung dari URL — nilai ini diinterpolasi ke SQL.
     */
    private String schema(String product) {
        return products.require(product).key();
    }

    /** Semua webhook yang pernah dijadwalkan (PENDING/SENT/FAILED), terbaru dulu. */
    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox(@PathVariable String product, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "20") int limit) {
        return db.sql("SELECT id, url, body, status, attempts, max_attempts, next_attempt_at, last_error, "
                        + "created_at, sent_at FROM " + schema(product) + ".webhook_outbox "
                        + "WHERE simulator_id = ? ORDER BY created_at DESC LIMIT ?")
                .param(id).param(Math.min(100, limit))
                .query().listOfRows();
    }

    // ---- Registrasi URL notifikasi (design.md §9.1) ----

    @GetMapping("/subscriptions")
    public List<WebhookSubscriptions.Subscription> listSubscriptions(@PathVariable String product,
                                                                     @PathVariable UUID id) {
        return products.require(product).webhooks().list(id);
    }

    /** Daftarkan/perbarui URL untuk (partner, event). Idempoten per pasangan itu. */
    @PostMapping("/subscriptions")
    public ResponseEntity<?> register(@PathVariable String product, @PathVariable UUID id,
                                      @RequestBody Map<String, String> body) {
        try {
            var created = products.require(product).webhooks().register(id,
                    body.get("partnerId"), body.get("event"), body.get("url"));
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<?> deleteSubscription(@PathVariable String product, @PathVariable UUID id,
                                                @PathVariable UUID subscriptionId) {
        try {
            products.require(product).webhooks().delete(id, subscriptionId);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Nonaktifkan tanpa menghapus — berguna untuk menguji "partner tak punya URL". */
    @PutMapping("/subscriptions/{subscriptionId}/status")
    public ResponseEntity<?> setStatus(@PathVariable String product, @PathVariable UUID id,
                                       @PathVariable UUID subscriptionId,
                                       @RequestBody Map<String, String> body) {
        try {
            products.require(product).webhooks().setStatus(id, subscriptionId, body.get("status"));
            return ResponseEntity.ok(Map.of("status", body.get("status")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Retry manual: ubah status FAILED → PENDING agar worker mengirim ulang. */
    @PostMapping("/outbox/{outboxId}/retry")
    public ResponseEntity<?> retry(@PathVariable String product, @PathVariable UUID id,
                                   @PathVariable UUID outboxId) {
        db.sql("UPDATE " + schema(product) + ".webhook_outbox SET status = 'PENDING', attempts = 0, "
                        + "next_attempt_at = ?, last_error = NULL "
                        + "WHERE id = ? AND simulator_id = ? AND status = 'FAILED'")
                .param(Timestamp.from(Instant.now())).param(outboxId).param(id)
                .update();
        return ResponseEntity.ok(Map.of("status", "retry-scheduled"));
    }
}

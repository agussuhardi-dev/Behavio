package id.behavio.web.admin;

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
 * <p><b>Webhook subscription dibuang (2026-07-14).</b> Dulu ada CRUD subscription di sini
 * (URL callback terdaftar per-partner), tapi tak ada satu pun kode engine yang membacanya
 * saat mengirim webhook — pengiriman nyata memakai URL dari header {@code X-CALLBACK-URL}
 * per-request (lihat {@code WebhookSpec.urlHeader}), dan dashboard tak pernah memanggilnya.
 * Fitur yang bisa didaftarkan tapi tak pernah berefek lebih berbahaya daripada tak ada,
 * jadi tabel + endpoint-nya dihapus.
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

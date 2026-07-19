package id.behavio.qris.platform.persistence;

import id.behavio.qris.platform.core.port.WebhookSubscriptions;
import id.behavio.qris.platform.core.rule.WebhookSpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WebhookSubscriptions} untuk satu schema produk (design.md §9.1).
 *
 * Generik seperti mesin konfigurasi lain: ditulis sekali, di-instansiasi per produk
 * dengan {@link SchemaTables} sebagai parameter — bukan dipercabangkan {@code if (qris)}.
 */
public class SchemaWebhookSubscriptions implements WebhookSubscriptions {

    private final JdbcClient db;
    private final SchemaTables t;

    public SchemaWebhookSubscriptions(JdbcClient db, SchemaTables tables) {
        this.db = db;
        this.t = tables;
    }

    /**
     * Cari registrasi event spesifik dulu, baru {@code ALL} (§9.1).
     *
     * Diurutkan lewat SQL (bukan dua query) agar "spesifik menang" jadi satu keputusan
     * atomik: dengan dua query, registrasi yang berubah di antaranya bisa membuat
     * notifikasi mendarat di URL yang salah tanpa jejak.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveUrl(UUID simulatorId, UUID partnerId, String event) {
        String key = event == null || event.isBlank() ? WebhookSpec.EVENT_ALL : event.trim();
        return db.sql("SELECT url FROM " + t.webhookSubscriptions()
                        + " WHERE simulator_id = ? AND partner_id = ? AND status = 'ACTIVE' "
                        + "AND event_type IN (?, ?) "
                        + "ORDER BY CASE WHEN event_type = ? THEN 0 ELSE 1 END LIMIT 1")
                .param(simulatorId).param(partnerId).param(key).param(WebhookSpec.EVENT_ALL).param(key)
                .query(String.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Subscription> list(UUID simulatorId) {
        return db.sql("SELECT s.id, p.partner_id, s.event_type, s.url, s.status FROM "
                        + t.webhookSubscriptions() + " s JOIN " + t.partners() + " p ON p.id = s.partner_id "
                        + "WHERE s.simulator_id = ? ORDER BY p.partner_id, s.event_type")
                .param(simulatorId)
                .query((rs, n) -> new Subscription(
                        rs.getObject("id", UUID.class), rs.getString("partner_id"),
                        rs.getString("event_type"), rs.getString("url"), rs.getString("status")))
                .list();
    }

    @Override
    @Transactional
    public Subscription register(UUID simulatorId, String partnerId, String event, String url) {
        requireUrl(url);
        String key = event == null || event.isBlank() ? WebhookSpec.EVENT_ALL : event.trim();
        UUID partnerRowId = db.sql("SELECT id FROM " + t.partners()
                        + " WHERE simulator_id = ? AND partner_id = ?")
                .param(simulatorId).param(partnerId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Partner tidak ditemukan: " + partnerId));

        UUID id = UUID.randomUUID();
        try {
            // Idempoten per (partner, event): mendaftar ulang = mengganti URL, bukan
            // menumpuk baris kedua yang membuat tujuan bergantung urutan baris.
            db.sql("INSERT INTO " + t.webhookSubscriptions()
                            + " (id, simulator_id, partner_id, url, event_type) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (simulator_id, partner_id, event_type) "
                            + "DO UPDATE SET url = EXCLUDED.url, status = 'ACTIVE'")
                    .param(id).param(simulatorId).param(partnerRowId).param(url.trim()).param(key)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Registrasi gagal: " + e.getMessage());
        }
        return db.sql("SELECT s.id, p.partner_id, s.event_type, s.url, s.status FROM "
                        + t.webhookSubscriptions() + " s JOIN " + t.partners() + " p ON p.id = s.partner_id "
                        + "WHERE s.simulator_id = ? AND s.partner_id = ? AND s.event_type = ?")
                .param(simulatorId).param(partnerRowId).param(key)
                .query((rs, n) -> new Subscription(
                        rs.getObject("id", UUID.class), rs.getString("partner_id"),
                        rs.getString("event_type"), rs.getString("url"), rs.getString("status")))
                .single();
    }

    @Override
    @Transactional
    public void delete(UUID simulatorId, UUID subscriptionId) {
        int deleted = db.sql("DELETE FROM " + t.webhookSubscriptions() + " WHERE id = ? AND simulator_id = ?")
                .param(subscriptionId).param(simulatorId)
                .update();
        if (deleted == 0) {
            throw new IllegalArgumentException("Registrasi tidak ditemukan: " + subscriptionId);
        }
    }

    @Override
    @Transactional
    public void setStatus(UUID simulatorId, UUID subscriptionId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!s.equals("ACTIVE") && !s.equals("INACTIVE")) {
            throw new IllegalArgumentException("Status harus ACTIVE atau INACTIVE, bukan: " + status);
        }
        int updated = db.sql("UPDATE " + t.webhookSubscriptions() + " SET status = ? "
                        + "WHERE id = ? AND simulator_id = ?")
                .param(s).param(subscriptionId).param(simulatorId)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Registrasi tidak ditemukan: " + subscriptionId);
        }
    }

    private static void requireUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL wajib diisi");
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("URL harus diawali http:// atau https://");
        }
    }
}

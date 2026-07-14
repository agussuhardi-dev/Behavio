package id.behavio.qris.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.qris.domain.QrisStatus;
import id.behavio.qris.domain.QrisTransaction;
import id.behavio.qris.domain.QrisType;
import id.behavio.qris.port.QrisRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi QrisRepository via tabel generik {@code entities}
 * (type='qris', data JSONB) — sesuai strategi hybrid storage (design.md §3.2).
 * Tanpa @Repository: bean ini dirakit eksplisit di *ProductConfig (kalau di-scan juga,
 * Spring melihat dua bean bertipe sama). Efek samping yang justru diinginkan — @Repository
 * memasang exception-translation AOP yang mengubah IllegalArgumentException jadi
 * InvalidDataAccessApiUsageException sebelum sampai ke controller, biang 500 mentah
 * menggantikan 400/409 yang rapi.
 */
public class QrisRepositoryJdbc implements QrisRepository {

    private final JdbcClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public QrisRepositoryJdbc(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void save(QrisTransaction qr) {
        String json = toJson(qr);
        int updated = db.sql("""
                UPDATE qris.entities SET data = ?::jsonb, status = ?
                WHERE simulator_id = ? AND partner_id = ? AND type = 'qris'
                  AND data->>'referenceNo' = ?
                """)
                .param(json).param(qr.status().name())
                .param(qr.simulatorId()).param(qr.partnerId()).param(qr.referenceNo())
                .update();
        if (updated == 0) {
            db.sql("""
                    INSERT INTO qris.entities (id, simulator_id, partner_id, type, data, status)
                    VALUES (?, ?, ?, 'qris', ?::jsonb, ?)
                    """)
                    .param(qr.id()).param(qr.simulatorId()).param(qr.partnerId())
                    .param(json).param(qr.status().name())
                    .update();
        }
    }

    @Override
    public Optional<QrisTransaction> find(UUID simulatorId, UUID partnerId, String referenceNo) {
        return db.sql("""
                SELECT data::text AS data, status FROM qris.entities
                WHERE simulator_id = ? AND partner_id = ? AND type = 'qris'
                  AND data->>'referenceNo' = ?
                """)
                .param(simulatorId).param(partnerId).param(referenceNo)
                .query((rs, n) -> fromJson(simulatorId, partnerId, rs.getString("data"), rs.getString("status")))
                .optional();
    }

    @Override
    public Optional<QrisTransaction> findAny(UUID simulatorId, String referenceNo) {
        return db.sql("""
                SELECT partner_id, data::text AS data, status FROM qris.entities
                WHERE simulator_id = ? AND type = 'qris' AND data->>'referenceNo' = ?
                """)
                .param(simulatorId).param(referenceNo)
                .query((rs, n) -> fromJson(simulatorId, rs.getObject("partner_id", UUID.class),
                        rs.getString("data"), rs.getString("status")))
                .optional();
    }

    @Override
    public List<QrisTransaction> list(UUID simulatorId, int limit, int offset) {
        // Tie-break `id` menjaga urutan deterministik antar-halaman bila created_at kembar.
        return db.sql("""
                SELECT partner_id, data::text AS data, status FROM qris.entities
                WHERE simulator_id = ? AND type = 'qris'
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """)
                .param(simulatorId).param(limit).param(offset)
                .query((rs, n) -> fromJson(simulatorId, rs.getObject("partner_id", UUID.class),
                        rs.getString("data"), rs.getString("status")))
                .list();
    }

    @Override
    public int count(UUID simulatorId) {
        return db.sql("SELECT count(*) FROM qris.entities WHERE simulator_id = ? AND type = 'qris'")
                .param(simulatorId)
                .query(Integer.class)
                .single();
    }

    private String toJson(QrisTransaction qr) {
        ObjectNode n = mapper.createObjectNode();
        n.put("partnerReferenceNo", qr.partnerReferenceNo());
        n.put("referenceNo", qr.referenceNo());
        n.put("merchantId", qr.merchantId());
        n.put("terminalId", qr.terminalId());
        n.put("qrType", qr.qrType().name());
        n.put("amount", qr.amount() == null ? null : qr.amount().toPlainString());
        n.put("currency", qr.currency());
        n.put("qrContent", qr.qrContent());
        n.put("callbackUrl", qr.callbackUrl());
        n.put("paidAmount", qr.paidAmount() == null ? null : qr.paidAmount().toPlainString());
        n.put("refundedAmount", qr.refundedAmount() == null ? null : qr.refundedAmount().toPlainString());
        n.put("paidAt", qr.paidAt() == null ? null : qr.paidAt().toString());
        n.put("createdAt", qr.createdAt().toString());
        return n.toString();
    }

    private QrisTransaction fromJson(UUID simulatorId, UUID partnerId, String json, String statusColumn) {
        try {
            JsonNode n = mapper.readTree(json);
            QrisStatus status = statusColumn == null ? QrisStatus.ACTIVE : QrisStatus.valueOf(statusColumn);
            QrisTransaction qt = new QrisTransaction(
                    UUID.randomUUID(), simulatorId, partnerId,
                    text(n, "partnerReferenceNo"), text(n, "referenceNo"), text(n, "merchantId"), text(n, "terminalId"),
                    QrisType.valueOf(text(n, "qrType")),
                    decimal(n, "amount"), text(n, "currency"), text(n, "qrContent"), text(n, "callbackUrl"),
                    status, decimal(n, "paidAmount"), decimal(n, "refundedAmount"),
                    n.hasNonNull("createdAt") ? Instant.parse(n.get("createdAt").asText()) : Instant.now());
            if (n.hasNonNull("paidAt")) {
                qt.markPaid(qt.paidAmount(), Instant.parse(n.get("paidAt").asText()));
            }
            return qt;
        } catch (Exception e) {
            throw new IllegalStateException("Gagal parse data QRIS", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : new BigDecimal(v.asText());
    }
}

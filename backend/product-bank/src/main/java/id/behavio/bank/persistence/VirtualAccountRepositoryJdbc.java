package id.behavio.bank.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.bank.domain.VirtualAccount;
import id.behavio.bank.domain.VirtualAccountStatus;
import id.behavio.bank.port.VirtualAccountRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi VirtualAccountRepository via tabel generik {@code entities}
 * (type='virtual_account', data JSONB) — sesuai strategi hybrid storage
 * (design.md §3.2): VA bukan pembawa uang langsung, jadi tak perlu tabel kaku.
 * Tanpa @Repository: bean ini dirakit eksplisit di *ProductConfig (kalau di-scan juga,
 * Spring melihat dua bean bertipe sama). Efek samping yang justru diinginkan — @Repository
 * memasang exception-translation AOP yang mengubah IllegalArgumentException jadi
 * InvalidDataAccessApiUsageException sebelum sampai ke controller, biang 500 mentah
 * menggantikan 400/409 yang rapi.
 */
public class VirtualAccountRepositoryJdbc implements VirtualAccountRepository {

    private final JdbcClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public VirtualAccountRepositoryJdbc(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void save(VirtualAccount va) {
        String json = toJson(va);
        int updated = db.sql("""
                UPDATE bank.entities SET data = ?::jsonb, status = ?
                WHERE simulator_id = ? AND partner_id = ? AND type = 'virtual_account'
                  AND data->>'virtualAccountNo' = ?
                """)
                .param(json).param(va.status().name())
                .param(va.simulatorId()).param(va.partnerId()).param(va.virtualAccountNo())
                .update();
        if (updated == 0) {
            db.sql("""
                    INSERT INTO bank.entities (id, simulator_id, partner_id, type, data, status)
                    VALUES (?, ?, ?, 'virtual_account', ?::jsonb, ?)
                    """)
                    .param(va.id()).param(va.simulatorId()).param(va.partnerId())
                    .param(json).param(va.status().name())
                    .update();
        }
    }

    @Override
    public Optional<VirtualAccount> find(UUID simulatorId, UUID partnerId, String virtualAccountNo) {
        return db.sql("""
                SELECT data::text AS data, status FROM bank.entities
                WHERE simulator_id = ? AND partner_id = ? AND type = 'virtual_account'
                  AND data->>'virtualAccountNo' = ?
                """)
                .param(simulatorId).param(partnerId).param(virtualAccountNo)
                .query((rs, n) -> fromJson(simulatorId, partnerId, rs.getString("data"), rs.getString("status")))
                .optional();
    }

    @Override
    public List<VirtualAccount> list(UUID simulatorId) {
        return db.sql("""
                SELECT partner_id, data::text AS data, status FROM bank.entities
                WHERE simulator_id = ? AND type = 'virtual_account' ORDER BY created_at DESC
                """)
                .param(simulatorId)
                .query((rs, n) -> fromJson(simulatorId,
                        rs.getObject("partner_id", UUID.class), rs.getString("data"), rs.getString("status")))
                .list();
    }

    @Override
    public void delete(UUID simulatorId, UUID partnerId, String virtualAccountNo) {
        db.sql("""
                DELETE FROM bank.entities WHERE simulator_id = ? AND partner_id = ? AND type = 'virtual_account'
                  AND data->>'virtualAccountNo' = ?
                """)
                .param(simulatorId).param(partnerId).param(virtualAccountNo)
                .update();
    }

    private String toJson(VirtualAccount va) {
        ObjectNode n = mapper.createObjectNode();
        n.put("partnerServiceId", va.partnerServiceId());
        n.put("customerNo", va.customerNo());
        n.put("virtualAccountNo", va.virtualAccountNo());
        n.put("virtualAccountName", va.virtualAccountName());
        n.put("virtualAccountEmail", va.virtualAccountEmail());
        n.put("virtualAccountPhone", va.virtualAccountPhone());
        n.put("totalAmount", va.totalAmount() == null ? null : va.totalAmount().toPlainString());
        n.put("currency", va.currency());
        n.put("virtualAccountTrxType", va.virtualAccountTrxType());
        n.put("expiredDate", va.expiredDate());
        n.put("trxId", va.trxId());
        // callbackUrl tak ditulis lagi (design.md §9.1). Baris VA lama yang masih memuat
        // field itu tetap terbaca — field-nya diabaikan, bukan bikin parse gagal.
        n.put("createdAt", va.createdAt().toString());
        return n.toString();
    }

    private VirtualAccount fromJson(UUID simulatorId, UUID partnerId, String json, String statusColumn) {
        try {
            JsonNode n = mapper.readTree(json);
            VirtualAccountStatus status = statusColumn == null
                    ? VirtualAccountStatus.ACTIVE : VirtualAccountStatus.valueOf(statusColumn);
            return new VirtualAccount(
                    UUID.randomUUID(), simulatorId, partnerId,
                    text(n, "partnerServiceId"), text(n, "customerNo"), text(n, "virtualAccountNo"),
                    text(n, "virtualAccountName"), text(n, "virtualAccountEmail"), text(n, "virtualAccountPhone"),
                    n.hasNonNull("totalAmount") ? new BigDecimal(n.get("totalAmount").asText()) : null,
                    text(n, "currency"), text(n, "virtualAccountTrxType"), text(n, "expiredDate"),
                    text(n, "trxId"),
                    status,
                    n.hasNonNull("createdAt") ? Instant.parse(n.get("createdAt").asText()) : Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Gagal parse data VA", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

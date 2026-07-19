package id.behavio.bank.platform.persistence;

import id.behavio.bank.platform.core.port.PartnerAdmin;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/** {@link PartnerAdmin} untuk satu schema produk — CRUD partner + kunci dari dashboard. */
public class SchemaPartnerAdmin implements PartnerAdmin {

    private final JdbcClient db;
    private final SchemaTables t;

    public SchemaPartnerAdmin(JdbcClient db, SchemaTables tables) {
        this.db = db;
        this.t = tables;
    }

    @Override
    public List<PartnerView> listPartners(UUID simulatorId) {
        return db.sql("SELECT id, partner_id, public_key, client_secret FROM " + t.partners()
                        + " WHERE simulator_id = ? ORDER BY partner_id")
                .param(simulatorId)
                .query((rs, n) -> new PartnerView(rs.getObject("id", UUID.class), rs.getString("partner_id"),
                        rs.getString("public_key") != null, rs.getString("client_secret") != null))
                .list();
    }

    @Override
    public UUID createPartner(UUID simulatorId, String partnerId, String publicKeyPem, String clientSecret) {
        UUID id = UUID.randomUUID();
        try {
            db.sql("INSERT INTO " + t.partners()
                            + " (id, simulator_id, partner_id, public_key, client_secret) VALUES (?, ?, ?, ?, ?)")
                    .param(id).param(simulatorId).param(partnerId)
                    .param(blankToNull(publicKeyPem)).param(blankToNull(clientSecret))
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Partner '" + partnerId + "' sudah ada di simulator ini");
        }
        return id;
    }

    @Override
    public void deletePartner(UUID simulatorId, UUID partnerRowId) {
        db.sql("DELETE FROM " + t.partners() + " WHERE id = ? AND simulator_id = ?")
                .param(partnerRowId).param(simulatorId).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

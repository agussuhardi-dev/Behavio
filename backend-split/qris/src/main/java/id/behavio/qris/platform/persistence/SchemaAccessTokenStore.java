package id.behavio.qris.platform.persistence;

import id.behavio.qris.platform.core.port.AccessTokenStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link AccessTokenStore} untuk satu schema produk. Bank & QRIS menerbitkan token B2B
 * masing-masing dari partner-nya sendiri; token bank tak pernah bisa dipakai di QRIS
 * karena tabelnya memang tabel yang berbeda.
 */
public class SchemaAccessTokenStore implements AccessTokenStore {

    private final JdbcClient db;
    private final SchemaTables t;

    public SchemaAccessTokenStore(JdbcClient db, SchemaTables tables) {
        this.db = db;
        this.t = tables;
    }

    @Override
    public void save(UUID simulatorId, UUID partnerId, String token, Instant issuedAt, Instant expiresAt) {
        db.sql("INSERT INTO " + t.accessTokens()
                        + " (id, simulator_id, partner_id, token, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(UUID.randomUUID()).param(simulatorId).param(partnerId).param(token)
                .param(Timestamp.from(issuedAt)).param(Timestamp.from(expiresAt))
                .update();
    }

    @Override
    public boolean isValid(UUID simulatorId, UUID partnerId, String token) {
        if (token == null || token.isBlank()) return false;
        Long count = db.sql("SELECT count(*) FROM " + t.accessTokens()
                        + " WHERE simulator_id = ? AND partner_id = ? AND token = ? AND expires_at > now()")
                .param(simulatorId).param(partnerId).param(token)
                .query(Long.class).single();
        return count != null && count > 0;
    }
}

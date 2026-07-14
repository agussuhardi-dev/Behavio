package id.behavio.persistence;

import id.behavio.core.port.AccessTokenStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Simpan access token B2B ke tabel access_tokens. */
@Repository
public class AccessTokenStoreJdbc implements AccessTokenStore {

    private final JdbcClient db;

    public AccessTokenStoreJdbc(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void save(UUID simulatorId, UUID partnerId, String token, Instant issuedAt, Instant expiresAt) {
        db.sql("""
                INSERT INTO access_tokens (id, simulator_id, partner_id, token, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID()).param(simulatorId).param(partnerId).param(token)
                .param(Timestamp.from(issuedAt)).param(Timestamp.from(expiresAt))
                .update();
    }

    @Override
    public boolean isValid(UUID simulatorId, UUID partnerId, String token) {
        if (token == null || token.isBlank()) return false;
        Long count = db.sql("""
                SELECT count(*) FROM access_tokens
                WHERE simulator_id = ? AND partner_id = ? AND token = ? AND expires_at > now()
                """)
                .param(simulatorId).param(partnerId).param(token)
                .query(Long.class).single();
        return count != null && count > 0;
    }
}

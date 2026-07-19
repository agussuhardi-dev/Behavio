package id.behavio.qris.platform.core.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port: simpan access token B2B yang diterbitkan (SNAP). Dipakai
 * endpoint /v1.0/access-token/b2b. Implementasi di adapter-persistence.
 */
public interface AccessTokenStore {

    void save(UUID simulatorId, UUID partnerId, String token, Instant issuedAt, Instant expiresAt);

    /**
     * Token ada, milik partner ini, dan belum kedaluwarsa. Dicek pada endpoint
     * transaksional saat mode STRICT — signature valid saja tidak cukup; token
     * harus benar-benar diterbitkan lewat access-token/b2b & belum expired.
     */
    boolean isValid(UUID simulatorId, UUID partnerId, String token);
}

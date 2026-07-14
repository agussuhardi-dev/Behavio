package id.behavio.core.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port: simpan access token B2B yang diterbitkan (SNAP). Dipakai
 * endpoint /v1.0/access-token/b2b. Implementasi di adapter-persistence.
 */
public interface AccessTokenStore {

    void save(UUID simulatorId, UUID partnerId, String token, Instant issuedAt, Instant expiresAt);
}

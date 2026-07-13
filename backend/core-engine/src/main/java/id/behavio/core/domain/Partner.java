package id.behavio.core.domain;

import java.util.UUID;

/**
 * Klien pemanggil (dikenali via X-PARTNER-ID). Menyimpan kunci signature.
 * Semua data state terisolasi penuh per-partner.
 */
public class Partner {

    private final UUID id;
    private final UUID simulatorId;
    private String partnerId;   // nilai X-PARTNER-ID
    private String publicKey;   // verifikasi RSA (access token)
    private String clientSecret; // verifikasi HMAC (transaksional)

    public Partner(UUID id, UUID simulatorId, String partnerId,
                   String publicKey, String clientSecret) {
        this.id = id;
        this.simulatorId = simulatorId;
        this.partnerId = partnerId;
        this.publicKey = publicKey;
        this.clientSecret = clientSecret;
    }

    public UUID id() { return id; }
    public UUID simulatorId() { return simulatorId; }
    public String partnerId() { return partnerId; }
    public String publicKey() { return publicKey; }
    public String clientSecret() { return clientSecret; }
}

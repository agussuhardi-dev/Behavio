package id.behavio.bank.platform.core.port;

/**
 * Outbound port: verifikasi signature SNAP.
 * Adapter-signature mengimplementasikan RSA-SHA256 (access token) &
 * HMAC-SHA512 (transaksional). Dipakai hanya pada mode STRICT.
 */
public interface SignatureVerifier {

    /** Access token B2B: RSA-SHA256 atas (clientId|timestamp). */
    boolean verifyAsymmetric(String clientId, String timestamp,
                             String signature, String publicKeyPem);

    /** Transaksional: HMAC-SHA512 atas (method:path:token:sha256(body):timestamp). */
    boolean verifySymmetric(String method, String relativeUrl, String accessToken,
                            String requestBody, String timestamp,
                            String signature, String clientSecret);
}

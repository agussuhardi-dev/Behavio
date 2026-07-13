package id.behavio.signature;

import id.behavio.core.port.SignatureVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Implementasi SignatureVerifier sesuai SNAP BI.
 *
 * - Asymmetric (access token B2B): SHA256withRSA atas "clientId|timestamp".
 * - Symmetric (transaksional): HMAC-SHA512 atas
 *   "METHOD:relativeUrl:accessToken:lowerhex(sha256(body)):timestamp".
 *
 * Pure Java (java.security / javax.crypto) — tanpa dependensi framework.
 */
public class SnapSignatureVerifier implements SignatureVerifier {

    @Override
    public boolean verifyAsymmetric(String clientId, String timestamp,
                                    String signature, String publicKeyPem) {
        try {
            String stringToSign = clientId + "|" + timestamp;
            PublicKey publicKey = parsePublicKey(publicKeyPem);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(stringToSign.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean verifySymmetric(String method, String relativeUrl, String accessToken,
                                   String requestBody, String timestamp,
                                   String signature, String clientSecret) {
        try {
            String bodyHash = lowerHexSha256(requestBody == null ? "" : requestBody);
            String stringToSign = String.join(":",
                    method, relativeUrl, accessToken, bodyHash, timestamp);
            String expected = hmacSha512Base64(stringToSign, clientSecret);
            return constantTimeEquals(expected, signature);
        } catch (Exception e) {
            return false;
        }
    }

    // --- helper ---

    /** Hitung signature simetris (untuk mode STRICT & untuk mengoreksi klien). */
    public String computeSymmetric(String method, String relativeUrl, String accessToken,
                                   String requestBody, String timestamp, String clientSecret) {
        try {
            String bodyHash = lowerHexSha256(requestBody == null ? "" : requestBody);
            String stringToSign = String.join(":",
                    method, relativeUrl, accessToken, bodyHash, timestamp);
            return hmacSha512Base64(stringToSign, clientSecret);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal menghitung signature", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String lowerHexSha256(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String hmacSha512Base64(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}

package id.behavio.signature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapSignatureVerifierTest {

    private final SnapSignatureVerifier verifier = new SnapSignatureVerifier();

    @Test
    void symmetric_signature_valid_round_trip() {
        String method = "POST";
        String url = "/v1.0/transfer-intrabank";
        String token = "abc123";
        String body = "{\"amount\":{\"value\":\"10000.00\",\"currency\":\"IDR\"}}";
        String ts = "2026-07-13T10:00:00+07:00";
        String secret = "super-secret";

        String sig = verifier.computeSymmetric(method, url, token, body, ts, secret);
        assertTrue(verifier.verifySymmetric(method, url, token, body, ts, sig, secret));
    }

    @Test
    void symmetric_signature_rejects_tampered_body() {
        String secret = "super-secret";
        String sig = verifier.computeSymmetric("POST", "/v1.0/transfer-intrabank",
                "abc123", "{\"amount\":\"10000\"}", "2026-07-13T10:00:00+07:00", secret);

        // body diubah → signature harus ditolak
        assertFalse(verifier.verifySymmetric("POST", "/v1.0/transfer-intrabank",
                "abc123", "{\"amount\":\"99999\"}", "2026-07-13T10:00:00+07:00", sig, secret));
    }
}

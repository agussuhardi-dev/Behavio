package id.behavio.bank.platform.web;

import id.behavio.bank.platform.core.domain.Partner;
import id.behavio.bank.platform.core.domain.SignatureMode;
import id.behavio.bank.platform.core.port.AccessTokenStore;
import id.behavio.bank.platform.core.port.ConfigRepository;
import id.behavio.bank.platform.core.port.SignatureVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint SNAP <b>Access Token B2B</b> (design.md Lampiran A.1):
 * POST /v1.0/access-token/b2b. Mode STRICT memverifikasi signature RSA asimetris
 * (clientId|timestamp) dengan public key partner; mode SIMULATED langsung terbit.
 *
 * Satu instance per PRODUK: bank & QRIS sama-sama menerbitkan token B2B, tapi dari
 * partner & tabel token schema-nya masing-masing — token bank tak berlaku di QRIS.
 */
public class AccessTokenService {

    private final ConfigRepository config;
    private final SignatureVerifier verifier;
    private final AccessTokenStore tokenStore;

    public AccessTokenService(ConfigRepository config, SignatureVerifier verifier, AccessTokenStore tokenStore) {
        this.config = config;
        this.verifier = verifier;
        this.tokenStore = tokenStore;
    }

    public record Result(int status, String body) {}

    public Result issue(UUID simulatorId, Map<String, String> headers, String body) {
        String clientKey = header(headers, "X-CLIENT-KEY");
        if (clientKey == null || clientKey.isBlank()) {
            return error(400, "4007301", "Invalid Mandatory Field X-CLIENT-KEY");
        }
        Optional<Partner> partnerOpt = config.findPartner(simulatorId, clientKey);
        if (partnerOpt.isEmpty()) {
            return error(401, "4017300", "Unauthorized. Unknown client");
        }
        Partner partner = partnerOpt.get();

        if (config.signatureMode(simulatorId) == SignatureMode.STRICT) {
            String timestamp = header(headers, "X-TIMESTAMP");
            String signature = header(headers, "X-SIGNATURE");
            boolean ok = signature != null && timestamp != null && partner.publicKey() != null
                    && verifier.verifyAsymmetric(clientKey, timestamp, signature, partner.publicKey());
            if (!ok) {
                return error(401, "4017300", "Unauthorized. Invalid Signature");
            }
        }

        String token = "BHV-" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        tokenStore.save(simulatorId, partner.id(), token, now, now.plusSeconds(900));

        String json = "{\"responseCode\":\"2007300\",\"responseMessage\":\"Successful\","
                + "\"accessToken\":\"" + token + "\",\"tokenType\":\"Bearer\",\"expiresIn\":\"900\"}";
        return new Result(200, json);
    }

    private Result error(int status, String code, String message) {
        return new Result(status,
                "{\"responseCode\":\"" + code + "\",\"responseMessage\":\"" + message + "\"}");
    }

    private static String header(Map<String, String> headers, String name) {
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}

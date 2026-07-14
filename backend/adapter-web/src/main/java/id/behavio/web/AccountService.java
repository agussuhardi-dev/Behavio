package id.behavio.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.core.domain.Account;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.StateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_AUTH = "Authorization";

    private final ConfigRepository config;
    private final StateRepository state;
    private final SignatureVerifier verifier;
    private final AccessTokenStore accessTokenStore;
    private final ObjectMapper mapper;

    public AccountService(ConfigRepository config, StateRepository state,
                          SignatureVerifier verifier, AccessTokenStore accessTokenStore,
                          ObjectMapper mapper) {
        this.config = config;
        this.state = state;
        this.verifier = verifier;
        this.accessTokenStore = accessTokenStore;
        this.mapper = mapper;
    }

    public record Result(int status, String body) {}

    /** Internal Account Inquiry — Service 15, POST /v1.0/account-inquiry-internal */
    @Transactional(readOnly = true)
    public Result internalInquiry(UUID simulatorId, String method, String path,
                                   Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String accountNo = text(n, "beneficiaryAccountNo");
        if (accountNo == null || accountNo.isBlank()) {
            return error(400, "4001502", "Invalid Mandatory Field beneficiaryAccountNo");
        }

        Optional<Account> accOpt = state.findAccount(simulatorId, partner.id(), accountNo);
        if (accOpt.isEmpty()) {
            return error(404, "4041511", "Invalid Account");
        }
        Account acc = accOpt.get();

        ObjectNode root = mapper.createObjectNode();
        root.put("responseCode", "2001500");
        root.put("responseMessage", "Request has been processed successfully");
        root.put("referenceNo", "REF-" + UUID.randomUUID().toString().substring(0, 12));
        root.put("partnerReferenceNo", text(n, "partnerReferenceNo"));
        root.put("beneficiaryAccountName", acc.holderName());
        root.put("beneficiaryAccountNo", acc.accountNo());
        root.put("beneficiaryAccountStatus", "Rekening aktif");
        root.put("beneficiaryAccountType", "S");
        root.put("currency", acc.currency());

        if (n.has("additionalInfo") && !n.get("additionalInfo").isNull()) {
            root.set("additionalInfo", n.get("additionalInfo"));
        }
        return new Result(200, root.toString());
    }

    /** Balance Inquiry — Service 11, POST /v1.0/balance-inquiry */
    @Transactional(readOnly = true)
    public Result balanceInquiry(UUID simulatorId, String method, String path,
                                  Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String accountNo = text(n, "accountNo");
        String bankCardToken = text(n, "bankCardToken");
        if ((accountNo == null || accountNo.isBlank()) && (bankCardToken == null || bankCardToken.isBlank())) {
            return error(400, "4001102", "Invalid Mandatory Field accountNo or bankCardToken");
        }

        Optional<Account> accOpt = state.findAccount(simulatorId, partner.id(), accountNo);
        if (accOpt.isEmpty()) {
            return error(404, "4041111", "Invalid Account");
        }
        Account acc = accOpt.get();

        ObjectNode root = mapper.createObjectNode();
        root.put("responseCode", "2001100");
        root.put("responseMessage", "Request has been processed successfully");
        root.put("referenceNo", "REF-" + UUID.randomUUID().toString().substring(0, 12));
        root.put("partnerReferenceNo", text(n, "partnerReferenceNo"));
        root.put("accountNo", acc.accountNo());
        root.put("name", acc.holderName());

        ArrayNode accountInfos = root.putArray("accountInfos");
        ObjectNode info = accountInfos.addObject();
        info.put("balanceType", "Cash");

        putAmount(info.putObject("amount"), acc.balance(), acc.currency());
        putAmount(info.putObject("floatAmount"), BigDecimal.ZERO, acc.currency());
        putAmount(info.putObject("holdAmount"), BigDecimal.ZERO, acc.currency());
        putAmount(info.putObject("availableBalance"), acc.balance(), acc.currency());
        putAmount(info.putObject("ledgerBalance"), acc.balance(), acc.currency());
        putAmount(info.putObject("currentMultilateralLimit"), BigDecimal.ZERO, acc.currency());

        info.put("registrationStatusCode", "0001");
        info.put("status", "0001");

        if (n.has("additionalInfo") && !n.get("additionalInfo").isNull()) {
            root.set("additionalInfo", n.get("additionalInfo"));
        }
        return new Result(200, root.toString());
    }

    // ---- auth ----

    private record AuthResult(Partner partner, Result error) {
        boolean failed() { return partner == null; }
    }

    private AuthResult authenticate(UUID simulatorId, String method, String path,
                                     Map<String, String> headers, String body) {
        String partnerHeader = header(headers, H_PARTNER);
        if (partnerHeader == null || partnerHeader.isBlank()) {
            return new AuthResult(null, error(400, "4000002", "Invalid Mandatory Field X-PARTNER-ID"));
        }
        Optional<Partner> partnerOpt = config.findPartner(simulatorId, partnerHeader);
        if (partnerOpt.isEmpty()) {
            return new AuthResult(null, error(401, "4010000", "Unauthorized. Unknown partner"));
        }
        Partner partner = partnerOpt.get();
        if (config.signatureMode(simulatorId) == SignatureMode.STRICT) {
            String timestamp = header(headers, H_TIMESTAMP);
            String signature = header(headers, H_SIGNATURE);
            String authHeader = header(headers, H_AUTH);
            String token = authHeader == null ? "" : authHeader.replaceFirst("(?i)^Bearer\\s+", "");
            if (!accessTokenStore.isValid(simulatorId, partner.id(), token)) {
                return new AuthResult(null, error(401, "4010001", "Unauthorized. Token invalid or expired"));
            }
            boolean ok = signature != null && timestamp != null && partner.clientSecret() != null
                    && verifier.verifySymmetric(method, path, token, body, timestamp, signature, partner.clientSecret());
            if (!ok) {
                return new AuthResult(null, error(401, "4010000", "Unauthorized. Invalid Signature"));
            }
        }
        return new AuthResult(partner, null);
    }

    // ---- helpers ----

    private void putAmount(ObjectNode node, BigDecimal value, String currency) {
        node.put("value", value == null ? "0.00" : value.toPlainString());
        node.put("currency", currency == null ? "IDR" : currency);
    }

    private JsonNode parseOrEmpty(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Result error(int status, String code, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("responseCode", code);
        n.put("responseMessage", message);
        return new Result(status, n.toString());
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
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

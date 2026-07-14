package id.behavio.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.domain.Transaction;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.StateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionHistoryService {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_AUTH = "Authorization";

    private final ConfigRepository config;
    private final StateRepository state;
    private final SignatureVerifier verifier;
    private final AccessTokenStore accessTokenStore;
    private final ObjectMapper mapper;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    public TransactionHistoryService(ConfigRepository config, StateRepository state,
                                      SignatureVerifier verifier, AccessTokenStore accessTokenStore,
                                      ObjectMapper mapper) {
        this.config = config;
        this.state = state;
        this.verifier = verifier;
        this.accessTokenStore = accessTokenStore;
        this.mapper = mapper;
    }

    public record Result(int status, String body) {}

    /** Transaction History List — Service 12, POST /v1.0/transaction-history-list */
    @Transactional(readOnly = true)
    public Result historyList(UUID simulatorId, String method, String path,
                               Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String fromStr = text(n, "fromDateTime");
        String toStr = text(n, "toDateTime");
        int pageSize = Math.min(50, n.path("pageSize").asInt(10));
        if (pageSize <= 0) pageSize = 10;
        int pageNumber = Math.max(0, n.path("pageNumber").asInt(0));

        Instant from = parseInstant(fromStr);
        Instant to = parseInstant(toStr);
        if (from == null) from = Instant.now().minusSeconds(86400 * 30);
        if (to == null) to = Instant.now();

        List<Transaction> transactions = state.findTransactions(
                simulatorId, partner.id(), from, to, pageSize, pageNumber * pageSize);

        ObjectNode root = mapper.createObjectNode();
        root.put("responseCode", "2001200");
        root.put("responseMessage", "Request has been processed successfully");
        root.put("referenceNo", "REF-" + UUID.randomUUID().toString().substring(0, 12));
        root.put("partnerReferenceNo", text(n, "partnerReferenceNo"));

        ArrayNode detail = root.putArray("detailData");
        for (Transaction txn : transactions) {
            ObjectNode item = detail.addObject();
            item.put("dateTime", txn.createdAt() == null ? ""
                    : OffsetDateTime.ofInstant(txn.createdAt(), WIB).format(ISO));
            putAmount(item.putObject("amount"), txn.amount(), txn.currency());
            item.put("remark", "Transfer " + txn.referenceNo());
            if ("SUCCESS".equals(txn.status().name())) {
                item.put("type", "PAYMENT");
            } else if ("FAILED".equals(txn.status().name())) {
                item.put("type", "PAYMENT");
            } else {
                item.put("type", "SEND_MONEY");
            }
            item.put("status", "FAILED".equals(txn.status().name()) ? "CANCELLED" : "SUCCESS");

            ArrayNode src = item.putArray("sourceOfFunds");
            ObjectNode fund = src.addObject();
            fund.put("source", "BALANCE");
            fund.putObject("amount").put("value", txn.amount().toPlainString())
                    .put("currency", txn.currency());
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

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
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

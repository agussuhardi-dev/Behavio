package id.behavio.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.QrisStatus;
import id.behavio.core.domain.QrisTransaction;
import id.behavio.core.domain.QrisType;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.engine.ConditionEvaluator;
import id.behavio.core.engine.EmvQrBuilder;
import id.behavio.core.engine.EvalContext;
import id.behavio.core.engine.ResponseRenderer;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.QrisRepository;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.WebhookSender;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint SNAP <b>QRIS MPM</b> (design.md Lampiran A3, ASPI SNAP spec).
 * Mencakup: Generate QR (48), Decode QR (48), Payment H2H (50), Query (51),
 * Cancel (77), Refund (78).
 *
 * Generate QR dievaluasi lewat Scenario/Rule yang sama persis dengan Transfer
 * Intrabank (product="qris"), sehingga response-nya dapat di-custom dari
 * dashboard.
 */
@Service
public class QrisService {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_AUTH = "Authorization";
    private static final String H_CALLBACK = "X-CALLBACK-URL";

    private final ConfigRepository config;
    private final SignatureVerifier verifier;
    private final AccessTokenStore accessTokenStore;
    private final QrisRepository qrisRepo;
    private final WebhookSender webhookSender;
    private final ObjectMapper mapper;
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ResponseRenderer renderer = new ResponseRenderer();

    public QrisService(ConfigRepository config, SignatureVerifier verifier, AccessTokenStore accessTokenStore,
                       QrisRepository qrisRepo, WebhookSender webhookSender, ObjectMapper mapper) {
        this.config = config;
        this.verifier = verifier;
        this.accessTokenStore = accessTokenStore;
        this.qrisRepo = qrisRepo;
        this.webhookSender = webhookSender;
        this.mapper = mapper;
    }

    public record Result(int status, String body) {}

    private record AuthResult(Partner partner, Result error) {
        boolean failed() { return partner == null; }
    }

    // ==================== Generate QR (service 47) ====================

    @Transactional
    public Result generate(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        Map<String, Object> fields = parseGenerateFields(body);

        Optional<Scenario> scenarioOpt = config.findActiveScenario(simulatorId, method, path);
        if (scenarioOpt.isEmpty()) {
            return error(404, "4040401", "No active scenario for endpoint");
        }
        EvalContext ctx = new EvalContext(fields, accNo -> null);
        Outcome outcome = pickOutcome(scenarioOpt.get(), ctx);

        boolean success = outcome.response().responseCode().startsWith("2");
        String referenceNo = "QR" + System.currentTimeMillis() + String.format("%03d", (int) (Math.random() * 1000));

        Map<String, Object> vars = new HashMap<>(fields);
        vars.put("referenceNo", referenceNo);
        vars.put("responseCode", outcome.response().responseCode());
        vars.put("responseMessage", outcome.response().responseMessage());
        Object amountField = fields.get("amount");
        vars.put("amountValue", amountField == null ? "" : amountField.toString());
        vars.putIfAbsent("currency", "IDR");
        vars.putIfAbsent("storeId", "");
        vars.putIfAbsent("terminalId", "");
        vars.putIfAbsent("merchantName", "BEHAVIO MERCHANT");
        vars.putIfAbsent("merchantId", "");
        vars.put("qrUrl", "");
        vars.put("redirectUrl", "");

        if (success) {
            BigDecimal amount = amountField == null ? null : new BigDecimal(amountField.toString());
            QrisType qrType = amount != null ? QrisType.DYNAMIC : QrisType.STATIC;
            String merchantId = str(fields.get("merchantId"));
            String qrContent = EmvQrBuilder.build(amount, merchantId, str(fields.get("merchantName")),
                    str(fields.get("merchantCity")), referenceNo);
            vars.put("qrContent", qrContent);

            QrisTransaction qr = new QrisTransaction(UUID.randomUUID(), simulatorId, partner.id(),
                    str(fields.get("partnerReferenceNo")), referenceNo, merchantId, str(fields.get("terminalId")),
                    qrType, amount, vars.get("currency").toString(), qrContent,
                    header(headers, H_CALLBACK), QrisStatus.ACTIVE, null, null, Instant.now());
            qrisRepo.save(qr);
        } else {
            vars.put("qrContent", "");
        }

        String responseBody = renderer.render(outcome.response().bodyTemplate(), vars);
        return new Result(outcome.response().httpStatus(), responseBody);
    }

    // ==================== Decode QR (service 48) ====================

    @Transactional(readOnly = true)
    public Result decode(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();

        JsonNode n = parseOrEmpty(body);
        String qrContent = text(n, "qrContent");
        if (qrContent == null || qrContent.isBlank()) {
            return error(400, "4004802", "Invalid Mandatory Field qrContent");
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2004800");
        vars.put("responseMessage", "Successful");
        vars.put("referenceNo", "QR" + System.currentTimeMillis() + String.format("%03d", (int) (Math.random() * 1000)));
        vars.put("partnerReferenceNo", text(n, "partnerReferenceNo"));
        vars.put("merchantName", "BEHAVIO MERCHANT");
        vars.put("merchantCategory", "Food & Beverage");
        vars.put("merchantLocation", "JAKARTA");
        vars.put("redirectUrl", "");
        vars.put("merchantPAN", "9360001410000000009");
        vars.put("acquirerName", "BEHAVIO");
        JsonNode amtNode = n.get("amount");
        vars.put("amountValue", amtNode != null && amtNode.hasNonNull("value") ? amtNode.get("value").asText() : "0.00");
        vars.put("currency", amtNode != null ? amtNode.path("currency").asText("IDR") : "IDR");
        vars.put("feeValue", "0.00");

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Apply OTT / Payment Redirect (service 49) ====================

    @Transactional(readOnly = true)
    public Result applyOtt(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();

        JsonNode n = parseOrEmpty(body);
        if (!n.has("userResources") || !n.get("userResources").isArray() || n.get("userResources").size() == 0) {
            return error(400, "4004902", "Invalid Mandatory Field userResources");
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2004900");
        vars.put("responseMessage", "Successful");
        vars.put("resourceType", n.get("userResources").get(0).asText("OTT"));
        vars.put("ottValue", UUID.randomUUID().toString().replace("-", ""));

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Payment H2H (service 50) ====================

    @Transactional
    public Result payment(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();

        JsonNode n = parseOrEmpty(body);
        String partnerRefNo = text(n, "partnerReferenceNo");
        JsonNode amtNode = n.get("amount");
        if (partnerRefNo == null || amtNode == null || !amtNode.hasNonNull("value")) {
            return error(400, "4005002", "Invalid Mandatory Field partnerReferenceNo/amount");
        }
        String referenceNo = "PAY" + System.currentTimeMillis() + String.format("%03d", (int) (Math.random() * 1000));

        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2005000");
        vars.put("responseMessage", "Successful");
        vars.put("referenceNo", referenceNo);
        vars.put("partnerReferenceNo", partnerRefNo);
        vars.put("transactionDate", Instant.now().toString());
        vars.put("amountValue", amtNode.get("value").asText());
        vars.put("currency", amtNode.path("currency").asText("IDR"));
        JsonNode feeNode = n.get("feeAmount");
        vars.put("feeValue", feeNode != null && feeNode.hasNonNull("value") ? feeNode.get("value").asText() : "0.00");
        vars.put("verificationId", text(n, "verificationId"));

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Query status (service 51) ====================

    @Transactional(readOnly = true)
    public Result query(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String originalReferenceNo = text(n, "originalReferenceNo");
        if (originalReferenceNo == null || originalReferenceNo.isBlank()) {
            return error(400, "4005102", "Invalid Mandatory Field originalReferenceNo");
        }
        Optional<QrisTransaction> qrOpt = qrisRepo.find(simulatorId, partner.id(), originalReferenceNo);
        if (qrOpt.isEmpty()) {
            return error(404, "4040001", "Transaction Not Found");
        }
        QrisTransaction qr = qrOpt.get();

        BigDecimal shownAmount = qr.status() == QrisStatus.PAID ? qr.paidAmount() : qr.amount();
        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2005100");
        vars.put("responseMessage", "Successful");
        vars.put("originalReferenceNo", qr.referenceNo());
        vars.put("originalPartnerReferenceNo", qr.partnerReferenceNo());
        vars.put("originalExternalId", n.hasNonNull("originalExternalId") ? n.get("originalExternalId").asText() : "");
        vars.put("serviceCode", n.hasNonNull("serviceCode") ? n.get("serviceCode").asText() : "47");
        vars.put("latestTransactionStatus", statusCode(qr.status()));
        vars.put("transactionStatusDesc", statusDesc(qr.status()));
        vars.put("paidTime", qr.status() == QrisStatus.PAID && qr.paidAt() != null ? qr.paidAt().toString() : "");
        vars.put("amountValue", shownAmount == null ? "0.00" : shownAmount.toPlainString());
        vars.put("currency", qr.currency());
        vars.put("feeValue", "0.00");
        vars.put("terminalId", qr.terminalId() != null ? qr.terminalId() : "");

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Refund (service 78) ====================

    @Transactional
    public Result refund(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String originalReferenceNo = text(n, "originalReferenceNo");
        String partnerRefundNo = text(n, "partnerRefundNo");
        JsonNode refundAmountNode = n.get("refundAmount");
        if (originalReferenceNo == null || refundAmountNode == null || !refundAmountNode.hasNonNull("value")) {
            return error(400, "4007801", "Invalid Mandatory Field originalReferenceNo/refundAmount");
        }
        BigDecimal refundAmount = new BigDecimal(refundAmountNode.get("value").asText());

        Optional<QrisTransaction> qrOpt = qrisRepo.find(simulatorId, partner.id(), originalReferenceNo);
        if (qrOpt.isEmpty() || qrOpt.get().status() != QrisStatus.PAID) {
            return error(404, "4040001", "Transaction Not Found");
        }
        QrisTransaction qr = qrOpt.get();
        if (refundAmount.signum() <= 0 || refundAmount.compareTo(qr.refundableAmount()) > 0) {
            return error(400, "4040013", "Invalid Amount");
        }

        qr.applyRefund(refundAmount);
        qrisRepo.save(qr);

        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2007800");
        vars.put("responseMessage", "Successful");
        vars.put("originalPartnerReferenceNo", text(n, "originalPartnerReferenceNo") != null ? text(n, "originalPartnerReferenceNo") : qr.partnerReferenceNo());
        vars.put("originalReferenceNo", qr.referenceNo());
        vars.put("originalExternalId", text(n, "originalExternalId"));
        vars.put("refundNo", "RFD" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        vars.put("partnerRefundNo", partnerRefundNo);
        vars.put("refundAmountValue", refundAmount.toPlainString());
        vars.put("currency", qr.currency());
        vars.put("reason", text(n, "reason"));
        vars.put("refundTime", Instant.now().toString());

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Cancel Payment (service 77) ====================

    @Transactional
    public Result cancel(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String originalReferenceNo = text(n, "originalReferenceNo");
        if (originalReferenceNo == null || originalReferenceNo.isBlank()) {
            return error(400, "4007702", "Invalid Mandatory Field originalReferenceNo");
        }
        Optional<QrisTransaction> qrOpt = qrisRepo.find(simulatorId, partner.id(), originalReferenceNo);
        if (qrOpt.isEmpty() || qrOpt.get().status() != QrisStatus.ACTIVE) {
            return error(404, "4040001", "Transaction Not Found");
        }
        QrisTransaction qr = qrOpt.get();
        qr.markExpired();
        qrisRepo.save(qr);

        Instant now = Instant.now();
        Map<String, Object> vars = new HashMap<>();
        vars.put("responseCode", "2007700");
        vars.put("responseMessage", "Successful");
        vars.put("originalPartnerReferenceNo", text(n, "originalPartnerReferenceNo") != null ? text(n, "originalPartnerReferenceNo") : qr.partnerReferenceNo());
        vars.put("originalReferenceNo", qr.referenceNo());
        vars.put("originalExternalId", text(n, "originalExternalId"));
        vars.put("cancelTime", now.toString());
        vars.put("transactionDate", qr.createdAt().toString());

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Admin: list, mark-paid, expire ====================

    @Transactional(readOnly = true)
    public java.util.List<QrisTransaction> list(UUID simulatorId) {
        return qrisRepo.list(simulatorId);
    }

    public record PayResult(boolean found, boolean webhookSent, String reason) {}

    @Transactional
    public PayResult markPaid(UUID simulatorId, String referenceNo, BigDecimal amountOverride) {
        Optional<QrisTransaction> qrOpt = qrisRepo.findAny(simulatorId, referenceNo);
        if (qrOpt.isEmpty()) {
            return new PayResult(false, false, "QR tidak ditemukan");
        }
        QrisTransaction qr = qrOpt.get();
        if (qr.status() != QrisStatus.ACTIVE) {
            return new PayResult(true, false, "QR berstatus " + qr.status() + " — hanya QR ACTIVE yang bisa dibayar");
        }
        BigDecimal paid = qr.qrType() == QrisType.DYNAMIC ? qr.amount() : amountOverride;
        if (paid == null) {
            return new PayResult(true, false, "QR static butuh nominal saat bayar");
        }
        Instant paidAt = Instant.now();
        qr.markPaid(paid, paidAt);
        qrisRepo.save(qr);

        if (qr.callbackUrl() == null || qr.callbackUrl().isBlank()) {
            return new PayResult(true, false, "Tidak ada X-CALLBACK-URL tersimpan saat generate");
        }
        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("originalReferenceNo", qr.referenceNo());
        notif.put("originalPartnerReferenceNo", qr.partnerReferenceNo());
        notif.put("latestTransactionStatus", "00");
        notif.put("transactionStatusDesc", "success");
        notif.put("amount", Map.of("value", paid.toPlainString(), "currency", qr.currency()));
        notif.put("merchantId", qr.merchantId());
        notif.put("paidTime", paidAt.toString());
        webhookSender.schedule(simulatorId, qr.callbackUrl(), Map.of("Content-Type", "application/json"),
                writeJson(notif), Duration.ZERO);
        return new PayResult(true, true, "Payment Notify dijadwalkan");
    }

    @Transactional
    public PayResult adminExpire(UUID simulatorId, String referenceNo) {
        Optional<QrisTransaction> qrOpt = qrisRepo.findAny(simulatorId, referenceNo);
        if (qrOpt.isEmpty()) {
            return new PayResult(false, false, "QR tidak ditemukan");
        }
        QrisTransaction qr = qrOpt.get();
        if (qr.status() != QrisStatus.ACTIVE) {
            return new PayResult(true, false, "Hanya QR berstatus ACTIVE yang bisa dikedaluwarsakan");
        }
        qr.markExpired();
        qrisRepo.save(qr);
        return new PayResult(true, false, "QR dikedaluwarsakan");
    }

    // ==================== helpers ====================

    /**
     * Render response lewat scenario engine — endpoint punya blueprint "Normal"
     * dengan template response ({{placeholders}}), dapat di-custom dari dashboard.
     * Bila tak ada scenario, render langsung dari vars (backward-compat).
     */
    private Result renderScenario(UUID simulatorId, String method, String path, Map<String, Object> vars) {
        Optional<Scenario> scenarioOpt = config.findActiveScenario(simulatorId, method, path);
        if (scenarioOpt.isEmpty() || scenarioOpt.get().fallback() == null) {
            return new Result(200, writeJson(vars));
        }
        Outcome outcome = scenarioOpt.get().fallback();
        String responseBody = renderer.render(outcome.response().bodyTemplate(), vars);
        return new Result(outcome.response().httpStatus(), responseBody);
    }

    private Outcome pickOutcome(Scenario scenario, EvalContext ctx) {
        for (Rule rule : scenario.rules()) {
            if (evaluator.evaluate(rule.when(), ctx)) {
                return rule.then();
            }
        }
        return scenario.fallback();
    }

    private Map<String, Object> parseGenerateFields(String body) {
        Map<String, Object> fields = new HashMap<>();
        JsonNode n = parseOrEmpty(body);
        putText(fields, n, "partnerReferenceNo");
        putText(fields, n, "merchantId");
        putText(fields, n, "terminalId");
        putText(fields, n, "storeId");
        putText(fields, n, "validityPeriod");
        JsonNode amount = n.get("amount");
        if (amount != null && amount.hasNonNull("value")) {
            fields.put("amount", new BigDecimal(amount.get("value").asText()));
            fields.put("currency", amount.path("currency").asText("IDR"));
        }
        JsonNode additionalInfo = n.get("additionalInfo");
        if (additionalInfo != null) {
            putText(fields, additionalInfo, "merchantName");
            putText(fields, additionalInfo, "merchantCity");
        }
        return fields;
    }

    private AuthResult authenticate(UUID simulatorId, String method, String path,
                                     Map<String, String> headers, String body) {
        String partnerHeader = header(headers, H_PARTNER);
        if (partnerHeader == null || partnerHeader.isBlank()) {
            return new AuthResult(null, error(400, "4004702", "Invalid Mandatory Field X-PARTNER-ID"));
        }
        Optional<Partner> partnerOpt = config.findPartner(simulatorId, partnerHeader);
        if (partnerOpt.isEmpty()) {
            return new AuthResult(null, error(401, "4014700", "Unauthorized. Unknown partner"));
        }
        Partner partner = partnerOpt.get();
        if (config.signatureMode(simulatorId) == SignatureMode.STRICT) {
            String timestamp = header(headers, H_TIMESTAMP);
            String signature = header(headers, H_SIGNATURE);
            String authHeader = header(headers, H_AUTH);
            String token = authHeader == null ? "" : authHeader.replaceFirst("(?i)^Bearer\\s+", "");
            if (!accessTokenStore.isValid(simulatorId, partner.id(), token)) {
                return new AuthResult(null, error(401, "4014701", "Unauthorized. Token invalid or expired"));
            }
            boolean ok = signature != null && timestamp != null && partner.clientSecret() != null
                    && verifier.verifySymmetric(method, path, token, body, timestamp, signature, partner.clientSecret());
            if (!ok) {
                return new AuthResult(null, error(401, "4014700", "Unauthorized. Invalid Signature"));
            }
        }
        return new AuthResult(partner, null);
    }

    private static String statusCode(QrisStatus s) {
        return switch (s) {
            case PAID -> "00";
            case REFUNDED -> "04";
            case EXPIRED -> "07";
            case ACTIVE -> "03";
        };
    }

    private static String statusDesc(QrisStatus s) {
        return switch (s) {
            case PAID -> "success";
            case REFUNDED -> "refunded";
            case EXPIRED -> "expired";
            case ACTIVE -> "pending";
        };
    }

    private JsonNode parseOrEmpty(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Result error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", code);
        body.put("responseMessage", message);
        return new Result(status, writeJson(body));
    }

    private static void putText(Map<String, Object> fields, JsonNode root, String name) {
        JsonNode v = root.get(name);
        if (v != null && !v.isNull()) fields.put(name, v.asText());
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String header(Map<String, String> headers, String name) {
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}

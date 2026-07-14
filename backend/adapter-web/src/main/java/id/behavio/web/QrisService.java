package id.behavio.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.core.blueprint.QrisMpmBlueprint;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint SNAP <b>QRIS MPM</b> (design.md Lampiran A3): Generate QR (static/dynamic),
 * Query status, Refund. Berbeda dari VA — Generate QR dievaluasi lewat Scenario/Rule
 * yang SAMA persis dengan Transfer Intrabank (product="qris", lihat ScenarioConfigPort),
 * sehingga response-nya <b>dapat di-custom dari dashboard</b> (editor request/response
 * yang sudah ada, bukan logika tetap). qrContent (EMV QR asli) & referenceNo adalah
 * computed var seperti referenceNo di transfer.
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

    // ---------------- Generate QR ----------------

    @Transactional
    public Result generate(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        Map<String, Object> fields = parseGenerateFields(body);

        Optional<Scenario> scenarioOpt = config.findActiveScenario(simulatorId, QrisMpmBlueprint.METHOD, QrisMpmBlueprint.PATH);
        if (scenarioOpt.isEmpty()) {
            return error(404, "4040401", "No active scenario for endpoint");
        }
        EvalContext ctx = new EvalContext(fields, accNo -> null); // QRIS tak pakai saldo
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

    // ---------------- Query status ----------------

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

        Map<String, Object> body2 = new java.util.LinkedHashMap<>();
        body2.put("responseCode", "2005100");
        body2.put("responseMessage", "Successful");
        body2.put("originalReferenceNo", qr.referenceNo());
        body2.put("originalPartnerReferenceNo", qr.partnerReferenceNo());
        body2.put("latestTransactionStatus", statusCode(qr.status()));
        body2.put("transactionStatusDesc", statusDesc(qr.status()));
        Map<String, Object> amount = new java.util.LinkedHashMap<>();
        BigDecimal shownAmount = qr.status() == QrisStatus.PAID ? qr.paidAmount() : qr.amount();
        amount.put("value", shownAmount == null ? "0.00" : shownAmount.toPlainString());
        amount.put("currency", qr.currency());
        body2.put("amount", amount);
        return new Result(200, writeJson(body2));
    }

    // ---------------- Refund ----------------

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
        if (qr.paidAmount() == null || refundAmount.compareTo(qr.paidAmount()) != 0) {
            return error(400, "4040013", "Invalid Amount"); // simulator: hanya full refund didukung
        }

        qr.markRefunded(refundAmount);
        qrisRepo.save(qr);

        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("responseCode", "2007800");
        resp.put("responseMessage", "Successful");
        resp.put("originalReferenceNo", qr.referenceNo());
        resp.put("originalPartnerReferenceNo", qr.partnerReferenceNo());
        resp.put("refundNo", "RFD" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        resp.put("partnerRefundNo", partnerRefundNo);
        Map<String, Object> refundAmt = new java.util.LinkedHashMap<>();
        refundAmt.put("value", refundAmount.toPlainString());
        refundAmt.put("currency", qr.currency());
        resp.put("refundAmount", refundAmt);
        resp.put("refundTime", Instant.now().toString());
        return new Result(200, writeJson(resp));
    }

    // ---------------- Admin: list & mark-paid (dashboard) ----------------

    @Transactional(readOnly = true)
    public java.util.List<QrisTransaction> list(UUID simulatorId) {
        return qrisRepo.list(simulatorId);
    }

    public record PayResult(boolean found, boolean webhookSent, String reason) {}

    /**
     * Aksi dashboard "tandai dibayar" (mensimulasikan pelanggan scan & bayar).
     * {@code amountOverride} WAJIB untuk QR static (nominal diisi saat bayar);
     * diabaikan untuk dynamic (pakai nominal tertanam).
     */
    @Transactional
    public PayResult markPaid(UUID simulatorId, String referenceNo, BigDecimal amountOverride) {
        Optional<QrisTransaction> qrOpt = qrisRepo.findAny(simulatorId, referenceNo);
        if (qrOpt.isEmpty()) {
            return new PayResult(false, false, "QR tidak ditemukan");
        }
        QrisTransaction qr = qrOpt.get();
        BigDecimal paid = qr.qrType() == QrisType.DYNAMIC ? qr.amount() : amountOverride;
        if (paid == null) {
            return new PayResult(true, false, "QR static butuh nominal saat bayar");
        }
        qr.markPaid(paid);
        qrisRepo.save(qr);

        if (qr.callbackUrl() == null || qr.callbackUrl().isBlank()) {
            return new PayResult(true, false, "Tidak ada X-CALLBACK-URL tersimpan saat generate");
        }
        Map<String, Object> notif = new java.util.LinkedHashMap<>();
        notif.put("originalReferenceNo", qr.referenceNo());
        notif.put("originalPartnerReferenceNo", qr.partnerReferenceNo());
        Map<String, Object> amount = new java.util.LinkedHashMap<>();
        amount.put("value", paid.toPlainString());
        amount.put("currency", qr.currency());
        notif.put("amount", amount);
        notif.put("latestTransactionStatus", "00");
        notif.put("transactionStatusDesc", "success");
        notif.put("merchantId", qr.merchantId());
        notif.put("paidTime", Instant.now().toString());
        webhookSender.schedule(simulatorId, qr.callbackUrl(), Map.of("Content-Type", "application/json"),
                writeJson(notif), Duration.ZERO);
        return new PayResult(true, true, "Payment Notify dijadwalkan");
    }

    // ---------------- helper ----------------

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
            case ACTIVE -> "03"; // pending/belum dibayar
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
        Map<String, Object> body = new java.util.LinkedHashMap<>();
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

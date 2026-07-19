package id.behavio.qris.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.qris.platform.core.domain.Partner;
import id.behavio.qris.domain.QrisStatus;
import id.behavio.qris.domain.QrisTransaction;
import id.behavio.qris.domain.QrisType;
import id.behavio.qris.platform.core.domain.SignatureMode;
import id.behavio.qris.platform.core.engine.ConditionEvaluator;
import id.behavio.qris.engine.EmvQrBuilder;
import id.behavio.qris.platform.core.engine.EvalContext;
import id.behavio.qris.platform.core.engine.ResponseRenderer;
import id.behavio.qris.platform.core.port.AccessTokenStore;
import id.behavio.qris.platform.core.port.ConfigRepository;
import id.behavio.qris.port.QrisRepository;
import id.behavio.qris.platform.core.port.SignatureVerifier;
import id.behavio.qris.platform.core.port.WebhookSender;
import id.behavio.qris.platform.core.port.WebhookSubscriptions;
import id.behavio.qris.platform.core.rule.FaultSpec;
import id.behavio.qris.platform.core.rule.Outcome;
import id.behavio.qris.platform.core.rule.Rule;
import id.behavio.qris.platform.core.rule.Scenario;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
public class QrisService {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_AUTH = "Authorization";

    /** Event Payment Notify (service 52) — kunci registrasi URL partner (design.md §9.1). */
    public static final String EVENT = "qris-payment";

    private final ConfigRepository config;
    private final SignatureVerifier verifier;
    private final AccessTokenStore accessTokenStore;
    private final QrisRepository qrisRepo;
    private final WebhookSender webhookSender;
    private final WebhookSubscriptions subscriptions;
    private final ObjectMapper mapper;
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ResponseRenderer renderer = new ResponseRenderer();

    /** Timestamp SNAP: ISO-8601 ber-offset WIB (mis. 2025-10-02T05:51:05+07:00), bukan Instant.toString(). */
    private static final DateTimeFormatter SNAP_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.of("Asia/Jakarta"));

    private static String ts(Instant at) {
        return at == null ? "" : SNAP_TS.format(at);
    }

    public QrisService(ConfigRepository config, SignatureVerifier verifier, AccessTokenStore accessTokenStore,
                       QrisRepository qrisRepo, WebhookSender webhookSender,
                       WebhookSubscriptions subscriptions, ObjectMapper mapper) {
        this.config = config;
        this.verifier = verifier;
        this.accessTokenStore = accessTokenStore;
        this.qrisRepo = qrisRepo;
        this.webhookSender = webhookSender;
        this.subscriptions = subscriptions;
        this.mapper = mapper;
    }

    /**
     * @param fault efek fisik (delay/drop/corrupt) yang diterapkan adapter web PASCA-commit
     *              (design.md §4.2), sama seperti jalur transfer. null = tanpa fault.
     */
    public record Result(int status, String body, FaultSpec fault) {
        public Result(int status, String body) {
            this(status, body, null);
        }
    }

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
                    QrisStatus.ACTIVE, null, null, Instant.now());
            qrisRepo.save(qr);
        } else {
            vars.put("qrContent", "");
        }

        String responseBody = renderer.render(outcome.response().bodyTemplate(), vars);
        return new Result(outcome.response().httpStatus(), responseBody, outcome.fault());
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
        vars.put("partnerReferenceNo", strOrEmpty(text(n, "partnerReferenceNo")));
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
        vars.put("transactionDate", ts(Instant.now()));
        vars.put("amountValue", amtNode.get("value").asText());
        vars.put("currency", amtNode.path("currency").asText("IDR"));
        JsonNode feeNode = n.get("feeAmount");
        vars.put("feeValue", feeNode != null && feeNode.hasNonNull("value") ? feeNode.get("value").asText() : "0.00");
        vars.put("verificationId", strOrEmpty(text(n, "verificationId")));

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
        vars.put("originalPartnerReferenceNo", strOrEmpty(qr.partnerReferenceNo()));
        vars.put("originalExternalId", n.hasNonNull("originalExternalId") ? n.get("originalExternalId").asText() : "");
        vars.put("serviceCode", n.hasNonNull("serviceCode") ? n.get("serviceCode").asText() : "47");
        vars.put("latestTransactionStatus", statusCode(qr.status()));
        vars.put("transactionStatusDesc", statusDesc(qr.status()));
        vars.put("paidTime", qr.status() == QrisStatus.PAID ? ts(qr.paidAt()) : "");
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
        vars.put("originalPartnerReferenceNo", strOrEmpty(text(n, "originalPartnerReferenceNo") != null ? text(n, "originalPartnerReferenceNo") : qr.partnerReferenceNo()));
        vars.put("originalReferenceNo", qr.referenceNo());
        vars.put("originalExternalId", strOrEmpty(text(n, "originalExternalId")));
        vars.put("refundNo", "RFD" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        vars.put("partnerRefundNo", strOrEmpty(partnerRefundNo));
        vars.put("refundAmountValue", refundAmount.toPlainString());
        vars.put("currency", qr.currency());
        vars.put("refundTime", ts(Instant.now()));

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
        vars.put("originalPartnerReferenceNo", strOrEmpty(text(n, "originalPartnerReferenceNo") != null ? text(n, "originalPartnerReferenceNo") : qr.partnerReferenceNo()));
        vars.put("originalReferenceNo", qr.referenceNo());
        vars.put("originalExternalId", strOrEmpty(text(n, "originalExternalId")));
        vars.put("cancelTime", ts(now));
        vars.put("transactionDate", ts(qr.createdAt()));

        return renderScenario(simulatorId, method, path, vars);
    }

    // ==================== Admin: list, mark-paid, expire ====================

    /** Satu halaman QR (terbaru dulu) + total, untuk paginator dashboard. */
    public record QrisPage(java.util.List<QrisTransaction> items, int total, int page, int size) {}

    @Transactional(readOnly = true)
    public QrisPage list(UUID simulatorId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int total = qrisRepo.count(simulatorId);
        // Halaman di luar jangkauan (mis. QR terhapus) → mundur ke halaman terakhir yang ada.
        int lastPage = total == 0 ? 0 : (total - 1) / safeSize;
        if (safePage > lastPage) safePage = lastPage;
        return new QrisPage(qrisRepo.list(simulatorId, safeSize, safePage * safeSize), total, safePage, safeSize);
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

        // Notifikasi terkirim OTOMATIS karena status berubah (design.md §9.2) — itu
        // jalur utamanya, meniru acquirer sungguhan.
        return notify(simulatorId, qr, "Payment Notify");
    }

    /**
     * Kirim ulang Payment Notify memakai status **aktif** QR — retry/test dari dashboard
     * (§9.2). Tidak mengubah QR-nya.
     *
     * BUKAN {@code readOnly}: menjadwalkan webhook tetap menulis baris ke outbox.
     */
    @Transactional
    public PayResult resendNotification(UUID simulatorId, String referenceNo) {
        Optional<QrisTransaction> qrOpt = qrisRepo.findAny(simulatorId, referenceNo);
        if (qrOpt.isEmpty()) {
            return new PayResult(false, false, "QR tidak ditemukan");
        }
        return notify(simulatorId, qrOpt.get(), "Notifikasi ulang");
    }

    /** Bangun & jadwalkan Payment Notify (service 52) dari status QR saat ini. */
    private PayResult notify(UUID simulatorId, QrisTransaction qr, String label) {
        Optional<String> url = subscriptions.resolveUrl(simulatorId, qr.partnerId(), EVENT);
        if (url.isEmpty()) {
            return new PayResult(true, false,
                    "Partner belum mendaftarkan URL notifikasi untuk event '" + EVENT + "' (design.md §9.1)");
        }
        BigDecimal notified = qr.paidAmount() != null ? qr.paidAmount() : qr.amount();

        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("originalReferenceNo", qr.referenceNo());
        notif.put("originalPartnerReferenceNo", strOrEmpty(qr.partnerReferenceNo()));
        // Status mengikuti status AKTIF QR (A3.4), bukan selalu "00" — mengirim "success"
        // untuk QR yang belum dibayar itu berbohong ke klien. Memakai statusCode/statusDesc
        // yang sudah dipakai qr-mpm-query, jadi notifikasi & query tak mungkin berbeda cerita.
        notif.put("latestTransactionStatus", statusCode(qr.status()));
        notif.put("transactionStatusDesc", statusDesc(qr.status()));
        notif.put("amount", Map.of("value", notified == null ? "0.00" : notified.toPlainString(),
                "currency", qr.currency()));
        // merchantId & paidTime BUKAN field top-level service 52 — daftar ASPI hanya mengenal
        // originalReferenceNo, originalPartnerReferenceNo, latestTransactionStatus,
        // transactionStatusDesc, customerNumber, accountType, destinationNumber,
        // destinationAccountName, amount, sessionId, bankCode, externalStoreId, additionalInfo.
        // additionalInfo justru didefinisikan untuk "custom use that are not provided by SNAP",
        // jadi di sinilah tempatnya (konvensi yang sama dipakai acquirer lain, mis. Finpay).
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("merchantId", strOrEmpty(qr.merchantId()));
        extra.put("terminalId", strOrEmpty(qr.terminalId()));
        extra.put("paidTime", qr.paidAt() != null ? ts(qr.paidAt()) : "");
        notif.put("additionalInfo", extra);
        webhookSender.schedule(simulatorId, url.get(), Map.of("Content-Type", "application/json"),
                writeJson(notif), Duration.ZERO);
        return new PayResult(true, true, label + " dijadwalkan ke " + url.get());
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
        return new Result(outcome.response().httpStatus(), responseBody, outcome.fault());
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

    /** SNAP mengirim string kosong, bukan null, untuk field opsional yang tak diisi. */
    private static String strOrEmpty(String s) { return s == null ? "" : s; }

    private static String header(Map<String, String> headers, String name) {
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}

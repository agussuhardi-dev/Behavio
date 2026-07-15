package id.behavio.bank.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.bank.domain.VirtualAccount;
import id.behavio.bank.domain.VirtualAccountStatus;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.bank.port.VirtualAccountRepository;
import id.behavio.core.port.WebhookSender;
import id.behavio.core.port.WebhookSubscriptions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint SNAP <b>Virtual Account</b> (design.md Lampiran A2): create-va, status
 * (inquiry), delete-va. Header & signature mengikuti pola transaksional SNAP yang
 * sama dengan Transfer Intrabank (Authorization Bearer + HMAC-SHA512 saat STRICT).
 *
 * responseCode: sukses pakai kode service-spesifik (2002700/2002600/2002500,
 * Lampiran A2.1); kegagalan pakai kode generik SNAP (service "00") — terverifikasi
 * dari dokumentasi Faspay: 4000002 (field wajib), 4010000 (unauthorized), 4040012
 * (VA tidak ditemukan), 4090001 (duplikat).
 */
public class VirtualAccountService {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_AUTH = "Authorization";

    /** Event notifikasi VA — kunci registrasi URL partner (design.md §9.1). */
    public static final String EVENT = "va-payment";

    private final ConfigRepository config;
    private final SignatureVerifier verifier;
    private final VirtualAccountRepository vaRepo;
    private final WebhookSender webhookSender;
    private final WebhookSubscriptions subscriptions;
    private final AccessTokenStore accessTokenStore;
    private final ObjectMapper mapper;

    public VirtualAccountService(ConfigRepository config, SignatureVerifier verifier,
                                 VirtualAccountRepository vaRepo, WebhookSender webhookSender,
                                 WebhookSubscriptions subscriptions,
                                 AccessTokenStore accessTokenStore, ObjectMapper mapper) {
        this.config = config;
        this.verifier = verifier;
        this.vaRepo = vaRepo;
        this.accessTokenStore = accessTokenStore;
        this.webhookSender = webhookSender;
        this.subscriptions = subscriptions;
        this.mapper = mapper;
    }

    public record Result(int status, String body) {}

    /** Hasil autentikasi: salah satu dari partner (sukses) atau error (gagal). */
    private record AuthResult(Partner partner, Result error) {
        boolean failed() { return partner == null; }
    }

    @Transactional
    public Result create(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n;
        try {
            n = mapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return error(400, "4000001", "Invalid Field Format body");
        }

        String partnerServiceId = text(n, "partnerServiceId");
        String customerNo = text(n, "customerNo");
        String vaNo = text(n, "virtualAccountNo");
        if (vaNo == null || vaNo.isBlank()) {
            vaNo = (partnerServiceId == null ? "" : partnerServiceId) + (customerNo == null ? "" : customerNo);
        }
        if (partnerServiceId == null || customerNo == null || vaNo.isBlank()) {
            return error(400, "4000002", "Invalid Mandatory Field partnerServiceId/customerNo");
        }
        if (vaRepo.find(simulatorId, partner.id(), vaNo).isPresent()) {
            return error(409, "4090001", "Duplicate Virtual Account");
        }

        JsonNode amountNode = n.get("totalAmount");
        BigDecimal amount = amountNode != null && amountNode.hasNonNull("value")
                ? new BigDecimal(amountNode.get("value").asText()) : BigDecimal.ZERO;
        String currency = amountNode != null ? amountNode.path("currency").asText("IDR") : "IDR";

        VirtualAccount va = new VirtualAccount(
                UUID.randomUUID(), simulatorId, partner.id(), partnerServiceId, customerNo, vaNo,
                text(n, "virtualAccountName"), text(n, "virtualAccountEmail"), text(n, "virtualAccountPhone"),
                amount, currency, text(n, "virtualAccountTrxType"), text(n, "expiredDate"),
                text(n, "trxId"), VirtualAccountStatus.ACTIVE, Instant.now());
        vaRepo.save(va);

        return ok(200, "2002700", "Successful", va);
    }

    @Transactional(readOnly = true)
    public Result inquiry(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String vaNo = text(n, "virtualAccountNo");
        if (vaNo == null || vaNo.isBlank()) {
            return error(400, "4000002", "Invalid Mandatory Field virtualAccountNo");
        }
        Optional<VirtualAccount> vaOpt = vaRepo.find(simulatorId, partner.id(), vaNo);
        if (vaOpt.isEmpty()) {
            return error(404, "4040012", "Invalid Bill/Virtual Account");
        }
        return ok(200, "2002600", "Successful", vaOpt.get());
    }

    @Transactional
    public Result delete(UUID simulatorId, String method, String path, Map<String, String> headers, String body) {
        AuthResult auth = authenticate(simulatorId, method, path, headers, body);
        if (auth.failed()) return auth.error();
        Partner partner = auth.partner();

        JsonNode n = parseOrEmpty(body);
        String vaNo = text(n, "virtualAccountNo");
        if (vaNo == null || vaNo.isBlank()) {
            return error(400, "4000002", "Invalid Mandatory Field virtualAccountNo");
        }
        if (vaRepo.find(simulatorId, partner.id(), vaNo).isEmpty()) {
            return error(404, "4040012", "Invalid Bill/Virtual Account");
        }
        vaRepo.delete(simulatorId, partner.id(), vaNo);
        ObjectNode body2 = mapper.createObjectNode();
        body2.put("responseCode", "2002500");
        body2.put("responseMessage", "Successful");
        return new Result(200, body2.toString());
    }

    /** Untuk Admin API/dashboard: daftar VA pada simulator (lintas partner). */
    @Transactional(readOnly = true)
    public List<VirtualAccount> list(UUID simulatorId) {
        return vaRepo.list(simulatorId);
    }

    public record PayResult(boolean found, boolean webhookSent, String reason) {}

    /**
     * Aksi dashboard "tandai dibayar": ubah status → PAID, lalu **otomatis** kirim
     * Payment Notification (design.md A2.3, §9.2). Ini jalur utamanya — notifikasi
     * terkirim karena statusnya berubah, seperti bank sungguhan.
     */
    @Transactional
    public PayResult markPaid(UUID simulatorId, String vaNo) {
        Optional<VirtualAccount> vaOpt = findVa(simulatorId, vaNo);
        if (vaOpt.isEmpty()) {
            return new PayResult(false, false, "VA tidak ditemukan");
        }
        VirtualAccount va = vaOpt.get();
        va.markPaid();
        vaRepo.save(va);
        return notify(simulatorId, va, "Payment Notification");
    }

    /**
     * Kirim ulang notifikasi memakai status **aktif** VA — retry/test dari dashboard
     * (§9.2). Tidak mengubah VA-nya: kalau tombol ini jadi satu-satunya cara notifikasi
     * terkirim, berarti auto-send di {@link #markPaid} rusak dan itu bug, bukan alur normal.
     *
     * BUKAN {@code readOnly}: menjadwalkan webhook tetap menulis baris ke outbox. "Tak
     * mengubah data" di sini berarti tak mengubah VA, bukan tak menulis apa pun.
     */
    @Transactional
    public PayResult resendNotification(UUID simulatorId, String vaNo) {
        Optional<VirtualAccount> vaOpt = findVa(simulatorId, vaNo);
        if (vaOpt.isEmpty()) {
            return new PayResult(false, false, "VA tidak ditemukan");
        }
        return notify(simulatorId, vaOpt.get(), "Notifikasi ulang");
    }

    private Optional<VirtualAccount> findVa(UUID simulatorId, String vaNo) {
        return vaRepo.list(simulatorId).stream()
                .filter(v -> v.virtualAccountNo().equals(vaNo))
                .findFirst();
    }

    /** Bangun & jadwalkan Payment Notification dari status VA saat ini (A2.3 + A2.4). */
    private PayResult notify(UUID simulatorId, VirtualAccount va, String label) {
        Optional<String> url = subscriptions.resolveUrl(simulatorId, va.partnerId(), EVENT);
        if (url.isEmpty()) {
            return new PayResult(true, false,
                    "Partner belum mendaftarkan URL notifikasi untuk event '" + EVENT + "' (design.md §9.1)");
        }

        ObjectNode paidAmount = mapper.createObjectNode();
        paidAmount.put("value", va.totalAmount() == null ? "0.00" : va.totalAmount().toPlainString());
        paidAmount.put("currency", va.currency());

        ObjectNode additionalInfo = mapper.createObjectNode();
        additionalInfo.put("paymentDate", Instant.now().toString());
        additionalInfo.put("channelCode", "TEST");
        additionalInfo.put("merchantId", va.partnerServiceId());
        // Status notifikasi mengikuti status AKTIF VA (A2.4), bukan selalu "00" —
        // mengirim "success" untuk VA yang belum dibayar itu berbohong ke klien.
        additionalInfo.put("latestTransactionStatus", snapStatus(va.status()));
        additionalInfo.put("transactionStatusDesc", statusDesc(va.status()));

        ObjectNode notif = mapper.createObjectNode();
        notif.put("partnerServiceId", va.partnerServiceId());
        notif.put("customerNo", va.customerNo());
        notif.put("virtualAccountNo", va.virtualAccountNo());
        notif.put("paymentRequestId", "PAY-" + UUID.randomUUID());
        if (va.trxId() != null) notif.put("trxId", va.trxId());
        notif.set("paidAmount", paidAmount);
        notif.put("trxDateTime", Instant.now().toString());
        notif.put("referenceNo", "REF-" + UUID.randomUUID().toString().substring(0, 12));
        notif.set("additionalInfo", additionalInfo);

        webhookSender.schedule(simulatorId, url.get(), Map.of("Content-Type", "application/json"),
                notif.toString(), Duration.ZERO);
        return new PayResult(true, true, label + " dijadwalkan ke " + url.get());
    }

    /** Status VA → latestTransactionStatus SNAP (design.md A2.4). */
    private static String snapStatus(VirtualAccountStatus status) {
        return switch (status) {
            case PAID -> "00";      // success
            case ACTIVE -> "03";    // pending
            case EXPIRED -> "07";   // expired
        };
    }

    private static String statusDesc(VirtualAccountStatus status) {
        return switch (status) {
            case PAID -> "success";
            case ACTIVE -> "pending";
            case EXPIRED -> "expired";
        };
    }

    // ---- helper ----

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
            // Token harus benar-benar diterbitkan & belum expired — signature saja
            // (HMAC) hanya menjamin integritas data, bukan keabsahan token itu sendiri.
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

    private JsonNode parseOrEmpty(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Result ok(int status, String code, String message, VirtualAccount va) {
        ObjectNode data = mapper.createObjectNode();
        data.put("partnerServiceId", va.partnerServiceId());
        data.put("customerNo", va.customerNo());
        data.put("virtualAccountNo", va.virtualAccountNo());
        data.put("virtualAccountName", va.virtualAccountName());
        if (va.virtualAccountEmail() != null) data.put("virtualAccountEmail", va.virtualAccountEmail());
        if (va.virtualAccountPhone() != null) data.put("virtualAccountPhone", va.virtualAccountPhone());
        ObjectNode amount = data.putObject("totalAmount");
        amount.put("value", va.totalAmount() == null ? "0.00" : va.totalAmount().toPlainString());
        amount.put("currency", va.currency());
        if (va.virtualAccountTrxType() != null) data.put("virtualAccountTrxType", va.virtualAccountTrxType());
        if (va.expiredDate() != null) data.put("expiredDate", va.expiredDate());
        if (va.trxId() != null) data.put("trxId", va.trxId());
        data.put("virtualAccountStatus", va.status().name());

        ObjectNode root = mapper.createObjectNode();
        root.put("responseCode", code);
        root.put("responseMessage", message);
        root.set("virtualAccountData", data);
        return new Result(status, root.toString());
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

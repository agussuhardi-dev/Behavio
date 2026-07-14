package id.behavio.core.blueprint;

import id.behavio.core.rule.Condition;
import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Operand;
import id.behavio.core.rule.CompareOp;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preset Blueprint SNAP BI untuk QRIS MPM — Generate QR, service 47
 * (design.md Lampiran A3.1–A3.2, A3.6).
 *
 * Scenario di sini = katalog LENGKAP responseCode service 47 menurut tabel ASPI
 * (apidevportal.aspi-indonesia.or.id, diverifikasi 2026-07-14). Preset ini hanya
 * titik awal — tiap scenario tetap dapat di-override penuh per simulator lewat
 * dashboard (§2, §8) memakai model Rule/Condition/Outcome/ResponseSpec yang sama.
 *
 * Catatan: Generate QR TIDAK memindahkan uang (tanpa Action Debit/Credit) — hanya
 * menghasilkan qrContent (dibangun EmvQrBuilder, computed var seperti referenceNo).
 */
public final class QrisMpmBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-generate";

    // ---- responseCode service 47, sesuai tabel ASPI ----
    public static final String RC_SUCCESS = "2004700";
    public static final String RC_BAD_REQUEST = "4004700";
    public static final String RC_INVALID_FIELD_FORMAT = "4004701";
    public static final String RC_INVALID_MANDATORY = "4004702";
    public static final String RC_UNAUTHORIZED = "4014700";
    public static final String RC_INVALID_TOKEN = "4014701";
    public static final String RC_TRX_EXPIRED = "4034700";
    public static final String RC_FEATURE_NOT_ALLOWED = "4034701";
    public static final String RC_EXCEEDS_AMOUNT_LIMIT = "4034702";
    public static final String RC_SUSPECTED_FRAUD = "4034703";
    public static final String RC_ACTIVITY_LIMIT = "4034704";
    public static final String RC_DO_NOT_HONOR = "4034705";
    public static final String RC_INVALID_MERCHANT = "4044708";
    public static final String RC_INVALID_TERMINAL = "4044717";
    public static final String RC_TOO_MANY_REQUESTS = "4294700";
    public static final String RC_GENERAL_ERROR = "5004700";
    public static final String RC_INTERNAL_ERROR = "5004701";
    public static final String RC_TIMEOUT = "5044700";

    /** Ambang default "Melebihi Limit" — ASPI tak menetapkan angka, ini preset simulator. */
    public static final BigDecimal DEFAULT_AMOUNT_LIMIT = new BigDecimal("10000000");

    /**
     * Nama scenario kanonik (urut: sukses → 4xx → 5xx). SATU sumber kebenaran — dipakai
     * provisioning DB maupun resolusi preset, supaya nama di tabel scenarios tak pernah
     * menyimpang dari yang dikenali blueprint.
     */
    public static final List<String> SCENARIO_NAMES = List.of(
            "Normal",
            "Bad Request",
            "Format Field Salah",
            "Field Wajib Kosong",
            "Unauthorized",
            "Token Tidak Valid",
            "Transaksi Kedaluwarsa",
            "Fitur Tidak Diizinkan",
            "Melebihi Limit",
            "Suspected Fraud",
            "Batas Aktivitas Terlampaui",
            "Do Not Honor",
            "Merchant Diblokir",
            "Terminal Tidak Valid",
            "Terlalu Banyak Request",
            "General Error",
            "Service Down",
            "Timeout"
    );

    private QrisMpmBlueprint() {}

    /** Resolusi nama scenario → preset. Nama tak dikenal → Normal. */
    public static Scenario byName(String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        return switch (key) {
            case "bad request" -> always("Bad Request", 400, RC_BAD_REQUEST, "Bad Request");
            case "format field salah" -> always("Format Field Salah", 400, RC_INVALID_FIELD_FORMAT, "Invalid Field Format amount");
            case "field wajib kosong" -> always("Field Wajib Kosong", 400, RC_INVALID_MANDATORY, "Invalid Mandatory Field merchantId");
            case "unauthorized" -> always("Unauthorized", 401, RC_UNAUTHORIZED, "Unauthorized. Signature");
            case "token tidak valid" -> always("Token Tidak Valid", 401, RC_INVALID_TOKEN, "Invalid Token (B2B)");
            case "transaksi kedaluwarsa" -> always("Transaksi Kedaluwarsa", 403, RC_TRX_EXPIRED, "Transaction Expired");
            case "fitur tidak diizinkan" -> always("Fitur Tidak Diizinkan", 403, RC_FEATURE_NOT_ALLOWED, "Feature Not Allowed");
            case "melebihi limit" -> exceedsLimit(DEFAULT_AMOUNT_LIMIT);
            case "suspected fraud" -> always("Suspected Fraud", 403, RC_SUSPECTED_FRAUD, "Suspected Fraud");
            case "batas aktivitas terlampaui" -> always("Batas Aktivitas Terlampaui", 403, RC_ACTIVITY_LIMIT, "Activity Count Limit Exceeded");
            case "do not honor" -> always("Do Not Honor", 403, RC_DO_NOT_HONOR, "Do Not Honor");
            case "merchant diblokir" -> merchantBlocked();
            case "terminal tidak valid" -> always("Terminal Tidak Valid", 404, RC_INVALID_TERMINAL, "Invalid Terminal");
            case "terlalu banyak request" -> always("Terlalu Banyak Request", 429, RC_TOO_MANY_REQUESTS, "Too Many Requests");
            case "general error" -> always("General Error", 500, RC_GENERAL_ERROR, "General Error");
            case "service down" -> serviceDown();
            case "timeout" -> timeout();
            default -> normal();
        };
    }

    /** Normal: tolak bila amount <= 0 (Invalid Field Format), selain itu QR berhasil dibuat. */
    public static Scenario normal() {
        Rule invalidAmount = new Rule(
                "Nominal tidak valid",
                new Condition.Compare(
                        new Operand.Field("amount"),
                        CompareOp.LTE,
                        new Operand.Num(BigDecimal.ZERO)),
                Outcome.of(errorResponse(400, RC_INVALID_FIELD_FORMAT, "Invalid Field Format amount")));
        return new Scenario("Normal", List.of(invalidAmount), successOutcome());
    }

    /** Merchant Diblokir: selalu tolak — merchant tidak terdaftar/nonaktif (ASPI 4044708). */
    public static Scenario merchantBlocked() {
        return always("Merchant Diblokir", 404, RC_INVALID_MERCHANT, "Invalid Merchant");
    }

    /** Service Down: layanan QRIS gangguan. ASPI service 47 tak punya kode 503 — dipakai 500. */
    public static Scenario serviceDown() {
        return always("Service Down", 500, RC_INTERNAL_ERROR, "Internal Server Error");
    }

    /** Timeout: respons sengaja lambat, lalu 504 (ASPI 5044700). */
    public static Scenario timeout() {
        return timeout(5000);
    }

    public static Scenario timeout(long delayMillis) {
        return new Scenario("Timeout", List.of(),
                Outcome.withFault(List.of(), errorResponse(504, RC_TIMEOUT, "Timeout"),
                        FaultSpec.delayAfter(delayMillis)));
    }

    /** Melebihi Limit: tolak hanya bila nominal di atas ambang (rule-based, seperti Transfer). */
    public static Scenario exceedsLimit(BigDecimal threshold) {
        Rule overLimit = new Rule(
                "Melebihi limit nominal",
                new Condition.Compare(
                        new Operand.Field("amount"),
                        CompareOp.GT,
                        new Operand.Num(threshold)),
                Outcome.of(errorResponse(403, RC_EXCEEDS_AMOUNT_LIMIT, "Exceeds Transaction Amount Limit")));
        return new Scenario("Melebihi Limit", List.of(overLimit), successOutcome());
    }

    // ---- helpers ----

    /** Scenario tanpa rule: apa pun requestnya, balas error yang sama. */
    private static Scenario always(String name, int status, String code, String message) {
        return new Scenario(name, List.of(), Outcome.of(errorResponse(status, code, message)));
    }

    private static Outcome successOutcome() {
        return Outcome.of(List.of(), successResponse());
    }

    private static ResponseSpec successResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("qrContent", "{{qrContent}}");
        body.put("qrUrl", "{{qrUrl}}");
        body.put("redirectUrl", "{{redirectUrl}}");
        body.put("merchantName", "{{merchantName}}");
        body.put("storeId", "{{storeId}}");
        body.put("terminalId", "{{terminalId}}");
        Map<String, Object> additionalInfo = new LinkedHashMap<>();
        additionalInfo.put("merchantId", "{{merchantId}}");
        body.put("additionalInfo", additionalInfo);
        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}

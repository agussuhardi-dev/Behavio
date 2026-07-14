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
 * Preset Blueprint SNAP BI untuk QRIS MPM — Generate QR (design.md Lampiran A3.1-A3.2).
 * Sama seperti Transfer Intrabank: preset ini titik awal, dapat di-override penuh per
 * simulator lewat dashboard (§2, §8) — memakai model Rule/Condition/Outcome/ResponseSpec
 * yang sama persis, hanya field & responseCode yang berbeda.
 *
 * Catatan: Generate QR TIDAK memindahkan uang (tanpa Action Debit/Credit) — hanya
 * menghasilkan qrContent (dibangun EmvQrBuilder, computed var seperti referenceNo).
 */
public final class QrisMpmBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-generate";

    public static final String RC_SUCCESS = "2004700";
    public static final String RC_INVALID_AMOUNT = "4004701";
    public static final String RC_MERCHANT_BLOCKED = "4044712";
    public static final String RC_SERVICE_DOWN = "5034700";

    private QrisMpmBlueprint() {}

    /** Normal: tolak bila amount <= 0, selain itu QR berhasil dibuat. */
    public static Scenario normal() {
        Rule invalidAmount = new Rule(
                "Nominal tidak valid",
                new Condition.Compare(
                        new Operand.Field("amount"),
                        CompareOp.LTE,
                        new Operand.Num(BigDecimal.ZERO)),
                Outcome.of(errorResponse(400, RC_INVALID_AMOUNT, "Invalid Amount")));
        return new Scenario("Normal", List.of(invalidAmount), successOutcome());
    }

    /** Merchant Diblokir: selalu tolak — merchant tidak terdaftar/nonaktif. */
    public static Scenario merchantBlocked() {
        return new Scenario("Merchant Diblokir", List.of(),
                Outcome.of(errorResponse(404, RC_MERCHANT_BLOCKED, "Invalid Merchant")));
    }

    /** Service Down: tolak di depan (503) — layanan QRIS sedang gangguan. */
    public static Scenario serviceDown() {
        return new Scenario("Service Down", List.of(),
                Outcome.of(errorResponse(503, RC_SERVICE_DOWN, "Service Unavailable")));
    }

    // ---- helpers ----

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

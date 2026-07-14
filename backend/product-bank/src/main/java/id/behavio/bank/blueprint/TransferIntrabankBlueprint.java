package id.behavio.bank.blueprint;

import id.behavio.bank.domain.TransactionStatus;
import id.behavio.bank.rule.BankAction;
import id.behavio.core.rule.Action;
import id.behavio.core.rule.CompareOp;
import id.behavio.core.rule.Condition;
import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Operand;
import id.behavio.core.rule.WebhookSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preset Blueprint SNAP BI untuk **Transfer Intrabank** (design.md Lampiran A.2) —
 * membangun 3 scenario Fase 1: Normal / Saldo Kurang / Limit. Semua nilai preset;
 * di produk nyata ini disimpan sebagai konfigurasi dan dapat di-override per-simulator.
 */
public final class TransferIntrabankBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transfer-intrabank";

    // responseCode SNAP = [HTTP 3][service 2][case 2] (service transfer intrabank = 17;
    // service 18 = transfer INTERbank, endpoint yang berbeda)
    public static final String RC_SUCCESS = "2001700";
    public static final String RC_INSUFFICIENT = "4001714";
    public static final String RC_LIMIT = "4031700";

    /** Batas nominal default untuk scenario "Limit". */
    public static final BigDecimal DEFAULT_LIMIT = new BigDecimal("25000000");

    private TransferIntrabankBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A.2). Nominal
     * sengaja bersarang seperti yang dikirim klien — {@code SnapRequestMapper} yang
     * meratakannya jadi {@code amount}/{@code currency} untuk engine.
     *
     * Rekening merujuk data seed ({@code BankBaseline}) agar contoh ini benar-benar
     * sukses saat langsung dikirim dari Postman, bukan sekadar bentuk yang benar.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", "15000.00");
        amount.put("currency", "IDR");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000001");
        body.put("amount", amount);
        body.put("beneficiaryAccountNo", "9876543210");
        body.put("sourceAccountNo", "1234567890");
        body.put("feeType", "OUR");
        body.put("remark", "Transfer test");
        body.put("transactionDate", "2026-07-15T10:00:00+07:00");
        body.put("additionalInfo", Map.of());
        return body;
    }

    /** Normal: tolak bila saldo < amount (4001714), selain itu transfer sukses (2001700). */
    public static Scenario normal() {
        Rule insufficient = new Rule(
                "Saldo tidak cukup",
                new Condition.Compare(
                        new Operand.AccountBalance("sourceAccountNo"),
                        CompareOp.LT,
                        new Operand.Field("amount")),
                Outcome.of(errorResponse(400, RC_INSUFFICIENT, "Insufficient Funds")));
        return new Scenario("Normal", List.of(insufficient), successOutcome());
    }

    /** Saldo Kurang (paksa): selalu balas 4001714 tanpa mengubah state. */
    public static Scenario forcedInsufficient() {
        return new Scenario("Saldo Kurang", List.of(),
                Outcome.of(errorResponse(400, RC_INSUFFICIENT, "Insufficient Funds")));
    }

    /** Limit: bila amount > limit → 4031700, selain itu transfer sukses. */
    public static Scenario limit() {
        return limit(DEFAULT_LIMIT);
    }

    public static Scenario limit(BigDecimal threshold) {
        Rule overLimit = new Rule(
                "Melebihi limit",
                new Condition.Compare(
                        new Operand.Field("amount"),
                        CompareOp.GT,
                        new Operand.Num(threshold)),
                Outcome.of(errorResponse(403, RC_LIMIT, "Exceeds Transaction Limit")));
        return new Scenario("Limit", List.of(overLimit), successOutcome());
    }

    // ---- scenario fault (design.md §4.2) ----

    public static final String RC_SERVICE_DOWN = "5030000";

    /** Bank Down: tolak di depan (503), saldo utuh — fault titik A. */
    public static Scenario bankDown() {
        return new Scenario("Bank Down", List.of(),
                Outcome.of(errorResponse(503, RC_SERVICE_DOWN, "Service Unavailable")));
    }

    /** Timeout: proses transfer lalu tunda respons (bank lambat). */
    public static Scenario timeout() {
        return timeout(5000);
    }

    public static Scenario timeout(long delayMillis) {
        return new Scenario("Timeout", List.of(),
                Outcome.withFault(successActions(), successResponse(), FaultSpec.delayAfter(delayMillis)));
    }

    /** Commit-Then-Drop (fault titik B): debit terjadi, respons hilang → uji idempotensi. */
    public static Scenario commitThenDrop() {
        return new Scenario("Commit Then Drop", List.of(),
                Outcome.withFault(successActions(), successResponse(), FaultSpec.commitThenDrop()));
    }

    /** Malformed (fault titik C): transfer sukses tapi body respons rusak. */
    public static Scenario malformed() {
        return new Scenario("Malformed", List.of(),
                Outcome.withFault(successActions(), successResponse(), FaultSpec.corruptAfter()));
    }

    /**
     * Async Callback (design.md §9): transfer diterima (respons PENDING), lalu 2 detik
     * kemudian simulator mengirim webhook status SUCCESS ke URL di header X-CALLBACK-URL.
     */
    public static Scenario asyncCallback() {
        WebhookSpec webhook = new WebhookSpec("X-CALLBACK-URL", 2000, notificationBody());
        return new Scenario("Async Callback", List.of(),
                Outcome.withWebhook(successActions(), pendingResponse(), webhook));
    }

    private static ResponseSpec pendingResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("latestTransactionStatus", "PENDING");
        return new ResponseSpec(200, RC_SUCCESS, "Accepted - awaiting callback", body);
    }

    private static Map<String, Object> notificationBody() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", "{{amountValue}}");
        amount.put("currency", "{{currency}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalReferenceNo", "{{referenceNo}}");
        body.put("originalPartnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("latestTransactionStatus", "00");
        body.put("transactionStatusDesc", "Success");
        body.put("amount", amount);
        return body;
    }

    // ---- helpers ----

    private static List<Action> successActions() {
        return List.of(
                new BankAction.Debit("sourceAccountNo", "amount"),
                new BankAction.Credit("beneficiaryAccountNo", "amount"),
                new BankAction.CreateTransaction(TransactionStatus.SUCCESS));
    }

    private static Outcome successOutcome() {
        return new Outcome(successActions(), successResponse());
    }

    private static ResponseSpec successResponse() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", "{{amountValue}}");
        amount.put("currency", "{{currency}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("amount", amount);
        body.put("beneficiaryAccountNo", "{{beneficiaryAccountNo}}");
        body.put("sourceAccountNo", "{{sourceAccountNo}}");
        body.put("transactionDate", "{{transactionDate}}");
        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}

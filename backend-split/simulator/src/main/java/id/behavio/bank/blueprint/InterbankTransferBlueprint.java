package id.behavio.bank.blueprint;

import id.behavio.bank.domain.TransactionStatus;
import id.behavio.bank.rule.BankAction;
import id.behavio.bank.platform.core.rule.Action;
import id.behavio.bank.platform.core.rule.CompareOp;
import id.behavio.bank.platform.core.rule.Condition;
import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Operand;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Rule;
import id.behavio.bank.platform.core.rule.Scenario;
import id.behavio.bank.platform.core.rule.WebhookSpec;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk Interbank Transfer (service 18).
 * Berbeda dari intrabank (service 17): hanya debit rekening sumber —
 * rekening tujuan di bank lain sehingga TIDAK dikredit secara internal.
 */
public final class InterbankTransferBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transfer-interbank";

    public static final String RC_SUCCESS = "2001800";
    public static final String RC_INSUFFICIENT = "4001814";
    public static final String RC_LIMIT = "4031800";
    public static final String RC_SERVICE_DOWN = "5030000";

    private InterbankTransferBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A.2 + service 18).
     * Beda dari intrabank: wajib {@code beneficiaryBankCode} — rekening tujuan ada di
     * bank lain, jadi tak perlu (dan tak akan) ditemukan di state simulator ini.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", "25000.00");
        amount.put("currency", "IDR");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000005");
        body.put("amount", amount);
        body.put("beneficiaryAccountNo", "8877665544");
        body.put("beneficiaryBankCode", "014");
        body.put("beneficiaryAccountName", "Citra Penerima");
        body.put("sourceAccountNo", "1234567890");
        body.put("transactionDate", "2026-07-15T10:00:00+07:00");
        body.put("additionalInfo", Map.of());
        return body;
    }

    /** Normal: tolak bila saldo < amount, selain itu transfer sukses (debit saja). */
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

    public static Scenario forcedInsufficient() {
        return new Scenario("Saldo Kurang", List.of(),
                Outcome.of(errorResponse(400, RC_INSUFFICIENT, "Insufficient Funds")));
    }

    public static Scenario limit() {
        return limit(new BigDecimal("25000000"));
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

    public static Scenario bankDown() {
        return new Scenario("Bank Down", List.of(),
                Outcome.of(errorResponse(503, RC_SERVICE_DOWN, "Service Unavailable")));
    }

    public static Scenario timeout() {
        return timeout(5000);
    }

    public static Scenario timeout(long delayMillis) {
        return new Scenario("Timeout", List.of(),
                Outcome.withFault(successActions(), successResponse(), FaultSpec.delayAfter(delayMillis)));
    }

    private static Outcome successOutcome() {
        return new Outcome(successActions(), successResponse());
    }

    private static List<Action> successActions() {
        return List.of(
                new BankAction.Debit("sourceAccountNo", "amount"),
                new BankAction.CreateTransaction(TransactionStatus.SUCCESS));
    }

    private static ResponseSpec successResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        body.put("beneficiaryAccountNo", "{{beneficiaryAccountNo}}");
        body.put("beneficiaryBankCode", "{{beneficiaryBankCode}}");
        body.put("sourceAccountNo", "{{sourceAccountNo}}");
        body.put("traceNo", "TRC" + System.currentTimeMillis() % 100000000);
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

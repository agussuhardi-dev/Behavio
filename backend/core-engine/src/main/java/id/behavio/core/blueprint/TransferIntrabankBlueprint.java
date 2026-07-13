package id.behavio.core.blueprint;

import id.behavio.core.domain.TransactionStatus;
import id.behavio.core.rule.Action;
import id.behavio.core.rule.CompareOp;
import id.behavio.core.rule.Condition;
import id.behavio.core.rule.Operand;
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

    // responseCode SNAP = [HTTP 3][service 2][case 2] (service transfer intrabank = 18)
    public static final String RC_SUCCESS = "2001800";
    public static final String RC_INSUFFICIENT = "4001714";
    public static final String RC_LIMIT = "4031800";

    /** Batas nominal default untuk scenario "Limit". */
    public static final BigDecimal DEFAULT_LIMIT = new BigDecimal("25000000");

    private TransferIntrabankBlueprint() {}

    /** Normal: tolak bila saldo < amount (4001714), selain itu transfer sukses (2001800). */
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

    /** Limit: bila amount > limit → 4031800, selain itu transfer sukses. */
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

    // ---- helpers ----

    private static Outcome successOutcome() {
        List<Action> actions = List.of(
                new Action.Debit("sourceAccountNo", "amount"),
                new Action.Credit("beneficiaryAccountNo", "amount"),
                new Action.CreateTransaction(TransactionStatus.SUCCESS));
        return new Outcome(actions, successResponse());
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

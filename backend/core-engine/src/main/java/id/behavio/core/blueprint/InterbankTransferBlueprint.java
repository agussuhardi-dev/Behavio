package id.behavio.core.blueprint;

import id.behavio.core.domain.TransactionStatus;
import id.behavio.core.rule.Action;
import id.behavio.core.rule.CompareOp;
import id.behavio.core.rule.Condition;
import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Operand;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;
import id.behavio.core.rule.WebhookSpec;

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

    private InterbankTransferBlueprint() {}

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

    private static Outcome successOutcome() {
        return new Outcome(successActions(), successResponse());
    }

    private static List<Action> successActions() {
        return List.of(
                new Action.Debit("sourceAccountNo", "amount"),
                new Action.CreateTransaction(TransactionStatus.SUCCESS));
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

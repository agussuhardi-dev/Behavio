package id.behavio.bank.blueprint;

import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Transaction History List (service 12).
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class TransactionHistoryListBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transaction-history-list";
    public static final String RC_SUCCESS = "2001200";
    public static final String RC_SERVICE_DOWN = "5030000";

    private TransactionHistoryListBlueprint() {}

    public static Scenario normal() {
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), normalResponse()));
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
                Outcome.withFault(List.of(), normalResponse(), FaultSpec.delayAfter(delayMillis)));
    }

    private static ResponseSpec normalResponse() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dateTime", "{{dateTime}}");
        item.put("amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        item.put("remark", "{{remark}}");
        item.put("sourceOfFunds", List.of(Map.of(
                "source", "BALANCE",
                "amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"))));
        item.put("status", "{{txnStatus}}");
        item.put("type", "{{txnType}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("detailData", List.of(item));

        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}
package id.behavio.bank.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Transaction History List (service 12).
 * Satu scenario "Normal" — response mengikuti spec ASPI.
 */
public final class TransactionHistoryListBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transaction-history-list";

    private TransactionHistoryListBlueprint() {}

    public static Scenario normal() {
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

        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2001200", "Successful", body)));
    }
}

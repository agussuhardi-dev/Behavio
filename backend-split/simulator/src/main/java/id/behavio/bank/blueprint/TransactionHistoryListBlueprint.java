package id.behavio.bank.blueprint;

import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;

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

    /** Contoh request untuk export OpenAPI (design.md §15.5). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000004");
        body.put("accountNo", "1234567890");
        body.put("fromDateTime", "2026-07-01T00:00:00+07:00");
        body.put("toDateTime", "2026-07-15T23:59:59+07:00");
        body.put("pageSize", "10");
        body.put("pageNumber", "1");
        body.put("additionalInfo", Map.of());
        return body;
    }

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
        // "@each" membuat baris ini diulang sekali per transaksi nyata di state
        // (ResponseRenderer). Tanpa ini, template merender SATU baris dari var request —
        // dan karena request service 12 tak punya dateTime/amount/remark, seluruh isinya
        // selalu "": riwayat yang tak pernah memuat riwayat. Nol transaksi → array kosong.
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");
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
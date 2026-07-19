package id.behavio.qris.blueprint;

import id.behavio.qris.platform.core.rule.Outcome;
import id.behavio.qris.platform.core.rule.ResponseSpec;
import id.behavio.qris.platform.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint QRIS Query (service 51).
 * Satu scenario "Normal" — response mengikuti spec ASPI.
 */
public final class QrisQueryBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-query";

    private QrisQueryBlueprint() {}

    /** Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.4 service 51). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalReferenceNo", "BHV17529000000001");
        body.put("originalPartnerReferenceNo", "2026071500000000000010");
        body.put("serviceCode", "47");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("originalReferenceNo", "{{originalReferenceNo}}");
        body.put("originalPartnerReferenceNo", "{{originalPartnerReferenceNo}}");
        body.put("originalExternalId", "{{originalExternalId}}");
        body.put("serviceCode", "{{serviceCode}}");
        body.put("latestTransactionStatus", "{{latestTransactionStatus}}");
        body.put("transactionStatusDesc", "{{transactionStatusDesc}}");
        body.put("paidTime", "{{paidTime}}");
        body.put("amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        body.put("feeAmount", Map.of("value", "{{feeValue}}", "currency", "IDR"));
        body.put("terminalId", "{{terminalId}}");
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2005100", "Successful", body)));
    }
}

package id.behavio.qris.blueprint;

import id.behavio.qris.platform.core.rule.Outcome;
import id.behavio.qris.platform.core.rule.ResponseSpec;
import id.behavio.qris.platform.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QrisDecodeBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-decode";

    private QrisDecodeBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.1 service 48).
     * {@code qrContent} = string EMV QR hasil {@code qr-mpm-generate} — satu-satunya
     * field wajib (tanpanya service membalas {@code 4004802}).
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalReferenceNo", "BHV17529000000001");
        body.put("qrContent", "00020101021226610014COM.BEHAVIO.WWW0118M00000010000000015204581253033605802ID"
                + "5910BEHAVIO QR6007JAKARTA54082500000063049B2A");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("merchantName", "{{merchantName}}");
        body.put("merchantCategory", "{{merchantCategory}}");
        body.put("merchantLocation", "{{merchantLocation}}");
        body.put("redirectUrl", "{{redirectUrl}}");
        body.put("merchantInfos", List.of(Map.of("merchantPAN", "{{merchantPAN}}",
                "acquirerName", "{{acquirerName}}")));
        body.put("transactionAmount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        body.put("feeAmount", Map.of("value", "{{feeValue}}", "currency", "IDR"));
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2004800", "Successful", body)));
    }
}

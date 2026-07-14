package id.behavio.qris.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QrisPaymentBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-payment";

    private QrisPaymentBlueprint() {}

    /** Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.1 service 50). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", "25000.00");
        amount.put("currency", "IDR");

        Map<String, Object> feeAmount = new LinkedHashMap<>();
        feeAmount.put("value", "0.00");
        feeAmount.put("currency", "IDR");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000011");
        body.put("amount", amount);
        body.put("feeAmount", feeAmount);
        body.put("merchantId", "M0000001");
        body.put("terminalId", "T0001");
        body.put("verificationId", "VER-0001");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("transactionDate", "{{transactionDate}}");
        body.put("amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        body.put("feeAmount", Map.of("value", "{{feeValue}}", "currency", "IDR"));
        body.put("verificationId", "{{verificationId}}");
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2005000", "Successful", body)));
    }
}

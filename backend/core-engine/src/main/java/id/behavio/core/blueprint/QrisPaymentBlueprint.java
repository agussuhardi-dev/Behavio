package id.behavio.core.blueprint;

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

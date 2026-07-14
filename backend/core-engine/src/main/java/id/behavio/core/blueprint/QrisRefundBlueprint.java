package id.behavio.core.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QrisRefundBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-refund";

    private QrisRefundBlueprint() {}

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("originalPartnerReferenceNo", "{{originalPartnerReferenceNo}}");
        body.put("originalReferenceNo", "{{originalReferenceNo}}");
        body.put("originalExternalId", "{{originalExternalId}}");
        body.put("refundNo", "{{refundNo}}");
        body.put("partnerRefundNo", "{{partnerRefundNo}}");
        body.put("refundAmount", Map.of("value", "{{refundAmountValue}}", "currency", "{{currency}}"));
        body.put("reason", "{{reason}}");
        body.put("refundTime", "{{refundTime}}");
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2007800", "Successful", body)));
    }
}

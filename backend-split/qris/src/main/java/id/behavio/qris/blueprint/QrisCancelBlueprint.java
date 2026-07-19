package id.behavio.qris.blueprint;

import id.behavio.qris.platform.core.rule.Outcome;
import id.behavio.qris.platform.core.rule.ResponseSpec;
import id.behavio.qris.platform.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QrisCancelBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/qr-mpm-cancel";

    private QrisCancelBlueprint() {}

    /** Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.1 service 77). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalReferenceNo", "BHV17529000000001");
        body.put("originalPartnerReferenceNo", "2026071500000000000010");
        body.put("reason", "Customer batal membayar");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("originalPartnerReferenceNo", "{{originalPartnerReferenceNo}}");
        body.put("originalReferenceNo", "{{originalReferenceNo}}");
        body.put("originalExternalId", "{{originalExternalId}}");
        body.put("cancelTime", "{{cancelTime}}");
        body.put("transactionDate", "{{transactionDate}}");
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2007700", "Successful", body)));
    }
}

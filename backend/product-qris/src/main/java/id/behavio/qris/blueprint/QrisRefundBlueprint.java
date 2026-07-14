package id.behavio.qris.blueprint;

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

    /** Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.1 service 78). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> refundAmount = new LinkedHashMap<>();
        refundAmount.put("value", "25000.00");
        refundAmount.put("currency", "IDR");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalReferenceNo", "BHV17529000000001");
        body.put("originalPartnerReferenceNo", "2026071500000000000010");
        body.put("partnerRefundNo", "RFD-2026-0715-001");
        body.put("refundAmount", refundAmount);
        body.put("reason", "Barang batal dibeli");
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
        body.put("refundNo", "{{refundNo}}");
        body.put("partnerRefundNo", "{{partnerRefundNo}}");
        body.put("refundAmount", Map.of("value", "{{refundAmountValue}}", "currency", "{{currency}}"));
        // `reason` adalah field REQUEST — tidak termasuk response refund ASPI.
        body.put("refundTime", "{{refundTime}}");
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2007800", "Successful", body)));
    }
}

package id.behavio.qris.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QrisApplyOttBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/qr/apply-ott";

    private QrisApplyOttBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A3.1 service 49).
     * {@code userResources} array (bukan objek) — service membacanya sebagai
     * {@code get(0).asText()} dan menolak dengan {@code 4004902} bila kosong.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userResources", List.of("OTT"));
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("userResources", List.of(Map.of("resourceType", "{{resourceType}}",
                "value", "{{ottValue}}")));
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2004900", "Successful", body)));
    }
}

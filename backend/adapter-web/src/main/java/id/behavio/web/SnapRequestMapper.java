package id.behavio.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Normalisasi body JSON SNAP → field datar yang dipakai Behavior Engine. Parsing JSON
 * adalah tanggung jawab adapter (core tetap murni). Fase 1: field Transfer Intrabank.
 */
@Component
public class SnapRequestMapper {

    private final ObjectMapper objectMapper;

    public SnapRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> toFields(String body) {
        Map<String, Object> fields = new HashMap<>();
        if (body == null || body.isBlank()) {
            return fields;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            putText(fields, root, "sourceAccountNo");
            putText(fields, root, "beneficiaryAccountNo");
            putText(fields, root, "partnerReferenceNo");
            putText(fields, root, "remark");
            JsonNode amount = root.get("amount");
            if (amount != null && amount.hasNonNull("value")) {
                fields.put("amount", new BigDecimal(amount.get("value").asText().trim()));
                fields.put("currency", amount.path("currency").asText("IDR"));
            }
        } catch (Exception e) {
            // biarkan fields apa adanya; engine akan menolak bila field wajib hilang
        }
        return fields;
    }

    private static void putText(Map<String, Object> fields, JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n != null && !n.isNull()) {
            fields.put(key, n.asText());
        }
    }
}

package id.behavio.qris.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Normalisasi body JSON SNAP → field datar yang dipakai Behavior Engine.
 * Semua field JSON diekstrak secara generik (rekursif untuk nested object)
 * sehingga template variable seperti {@code {{accountNo}}} atau
 * {@code {{virtualAccountNo}}} tersedia di engine tanpa perubahan mapper.
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
            flatten("", root, fields);
        } catch (Exception e) {
            // biarkan fields apa adanya; engine akan menolak bila field wajib hilang
        }
        return fields;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> out) {
        if (node.isObject()) {
            boolean top = prefix.isEmpty();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = top ? e.getKey() : prefix + "." + e.getKey();
                JsonNode child = e.getValue();
                if (child.isObject() || child.isArray()) {
                    if (top && child.isObject()) {
                        if ("amount".equals(e.getKey()) && child.hasNonNull("value")) {
                            out.put("amount", new BigDecimal(child.get("value").asText()));
                            out.put("currency", child.path("currency").asText("IDR"));
                        }
                        if ("totalAmount".equals(e.getKey()) && child.hasNonNull("value")) {
                            out.put("totalAmount.value", child.get("value").asText());
                            out.put("totalAmount.currency", child.path("currency").asText("IDR"));
                        }
                    }
                    flatten(key, child, out);
                } else if (child.isNumber()) {
                    out.put(key, new BigDecimal(child.asText()));
                } else {
                    out.put(key, child.asText());
                }
            }
        }
    }
}

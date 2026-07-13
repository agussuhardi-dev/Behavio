package id.behavio.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merender body response dari template: mengganti placeholder {@code {{var}}} dengan
 * nilai dari {@code vars} (field request + hasil: referenceNo, responseCode, dll),
 * lalu menulisnya jadi JSON. Template boleh bersarang (Map/List).
 */
public final class ResponseRenderer {

    /** @return body JSON string hasil render. */
    public String render(Map<String, Object> template, Map<String, Object> vars) {
        Object rendered = renderValue(template, vars);
        return Json.write(rendered);
    }

    @SuppressWarnings("unchecked")
    private Object renderValue(Object node, Map<String, Object> vars) {
        return switch (node) {
            case null -> null;
            case String s -> substitute(s, vars);
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), renderValue(e.getValue(), vars));
                }
                yield out;
            }
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for (Object item : l) out.add(renderValue(item, vars));
                yield out;
            }
            default -> node;
        };
    }

    /**
     * Bila string berupa satu placeholder utuh {@code {{key}}}, kembalikan nilai
     * aslinya (mempertahankan tipe non-string). Selain itu substitusi in-place jadi string.
     */
    private Object substitute(String s, Map<String, Object> vars) {
        String trimmed = s.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) == -1) {
            String key = trimmed.substring(2, trimmed.length() - 2).trim();
            return vars.getOrDefault(key, "");
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("{{", i);
            if (open < 0) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, open);
            int close = s.indexOf("}}", open + 2);
            if (close < 0) { sb.append(s, open, s.length()); break; }
            String key = s.substring(open + 2, close).trim();
            Object val = vars.get(key);
            sb.append(val == null ? "" : val.toString());
            i = close + 2;
        }
        return sb.toString();
    }
}

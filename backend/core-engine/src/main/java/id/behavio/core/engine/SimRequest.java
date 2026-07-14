package id.behavio.core.engine;

import java.util.Map;

/**
 * Request masuk yang dinormalkan untuk Behavior Engine (bebas framework web).
 *
 * @param method  HTTP method
 * @param path    path relatif (mis. {@code /v1.0/transfer-intrabank})
 * @param headers header (nama sesuai SNAP, mis. X-PARTNER-ID, X-EXTERNAL-ID)
 * @param fields  field body yang sudah dinormalkan adapter (mis. {@code amount}→BigDecimal,
 *                {@code sourceAccountNo}, {@code beneficiaryAccountNo}, {@code partnerReferenceNo}).
 *                Parsing JSON dilakukan adapter agar core tetap murni.
 * @param rawBody body mentah (untuk signature/log)
 */
public record SimRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, Object> fields,
        String rawBody
) {
    /** Lookup header case-insensitive (adapter bisa menormalkan casing berbeda). */
    public String header(String name) {
        if (headers == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}

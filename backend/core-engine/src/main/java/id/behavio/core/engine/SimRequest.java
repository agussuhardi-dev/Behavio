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
    public String header(String name) {
        return headers == null ? null : headers.get(name);
    }
}

package id.behavio.core.engine;

import java.util.Map;

/** Response yang dihasilkan Behavior Engine (dipetakan adapter web ke HTTP). */
public record SimResponse(
        int httpStatus,
        String responseCode,
        Map<String, String> headers,
        String body
) {
    public static SimResponse of(int httpStatus, String responseCode, String body) {
        return new SimResponse(httpStatus, responseCode, Map.of(), body);
    }
}

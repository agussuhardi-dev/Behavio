package id.behavio.qris.platform.core.port;

import java.util.Map;

/**
 * Outbound port: siarkan event request untuk Live View (SSE).
 * Dipublikasikan ke Event Bus, diteruskan ke dashboard.
 */
public interface EventPublisher {

    void publishRequestEvent(RequestEvent event);

    /**
     * Satu request untuk live view. Field ringkasan (method/path/status/…) dipakai
     * RequestLogWriter + baris ringkas; {@code requestHeaders/requestBody/responseBody}
     * detail penuh untuk debugging (hanya disiarkan via SSE, tidak dipersist).
     */
    record RequestEvent(
            String simulatorId,
            String method,
            String path,
            int httpStatus,
            String responseCode,
            long durationMillis,
            Map<String, String> requestHeaders,
            String requestBody,
            String responseBody
    ) {
        /** Konstruktor ringkas (tanpa detail) — kompatibel pemanggil lama. */
        public RequestEvent(String simulatorId, String method, String path,
                            int httpStatus, String responseCode, long durationMillis) {
            this(simulatorId, method, path, httpStatus, responseCode, durationMillis, null, null, null);
        }
    }
}

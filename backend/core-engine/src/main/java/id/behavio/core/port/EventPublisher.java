package id.behavio.core.port;

/**
 * Outbound port: siarkan event request untuk Live View (SSE).
 * Dipublikasikan ke Event Bus, diteruskan ke dashboard.
 */
public interface EventPublisher {

    void publishRequestEvent(RequestEvent event);

    /** Ringkasan satu request untuk live view. */
    record RequestEvent(
            String simulatorId,
            String method,
            String path,
            int httpStatus,
            String responseCode,
            long durationMillis
    ) {}
}

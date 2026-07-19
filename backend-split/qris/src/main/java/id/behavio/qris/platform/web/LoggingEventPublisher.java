package id.behavio.qris.platform.web;

import id.behavio.qris.platform.core.port.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Event publisher Live View — Fase 1: log ringkas per request. Fase berikutnya:
 * Event Bus in-app → SSE ke dashboard (design.md §10).
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger("behavio.liveview");

    @Override
    public void publishRequestEvent(RequestEvent e) {
        log.info("{} {} → {} {} ({} ms)  sim={}",
                e.method(), e.path(), e.httpStatus(), e.responseCode(), e.durationMillis(), e.simulatorId());
    }
}

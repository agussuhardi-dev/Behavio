package id.behavio.webhook;

import id.behavio.core.port.WebhookSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Outbound adapter webhook — Fase 0 skeleton.
 * Fase 2: persistent outbox (tabel) + worker pengirim + retry.
 * Untuk kini hanya mencatat penjadwalan.
 */
@Component
public class LoggingOutboxWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxWebhookSender.class);

    @Override
    public void schedule(String url, Map<String, String> headers, String body, Duration delay) {
        log.info("[webhook-stub] jadwalkan ke {} setelah {} (Fase 2: outbox+retry)", url, delay);
    }
}

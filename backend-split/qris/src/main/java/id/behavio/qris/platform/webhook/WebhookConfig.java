package id.behavio.qris.platform.webhook;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Mengaktifkan penjadwalan untuk WebhookWorker (outbox dispatcher). */
@Configuration
@EnableScheduling
public class WebhookConfig {
}

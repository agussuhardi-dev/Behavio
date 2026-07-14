package id.behavio.app.config;

import id.behavio.core.engine.BehaviorEngine;
import id.behavio.core.engine.DefaultBehaviorEngine;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.StateRepository;
import id.behavio.core.port.WebhookSender;
import id.behavio.signature.SnapSignatureVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Perakitan Hexagonal: menyambung core-engine (murni) dengan adapter.
 * DefaultBehaviorEngine dirakit di sini dengan port dari adapter-persistence,
 * dan EventPublisher gabungan (log + RequestLog + SSE).
 */
@Configuration
public class CoreBeansConfig {

    @Bean
    public SignatureVerifier signatureVerifier() {
        return new SnapSignatureVerifier();
    }

    /**
     * Behavior Engine dirakit dari port. {@code publishers} = semua EventPublisher
     * (LoggingEventPublisher, RequestLogWriter, SseBroadcaster) → fan-out.
     */
    @Bean
    public BehaviorEngine behaviorEngine(StateRepository state,
                                         ConfigRepository config,
                                         SignatureVerifier signatureVerifier,
                                         WebhookSender webhookSender,
                                         AccessTokenStore accessTokenStore,
                                         List<EventPublisher> publishers) {
        EventPublisher composite = event -> publishers.forEach(p -> p.publishRequestEvent(event));
        return new DefaultBehaviorEngine(state, config, composite, signatureVerifier, webhookSender, accessTokenStore);
    }
}

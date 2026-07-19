package id.behavio.qris.platform;

import id.behavio.qris.platform.core.port.SignatureVerifier;
import id.behavio.qris.platform.persistence.SchemaTables;
import id.behavio.qris.platform.signature.SnapSignatureVerifier;
import id.behavio.qris.platform.webhook.WebhookWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * Perakitan platform milik module QRIS (paralel dengan {@code bank.platform}). Sejak
 * pemisahan penuh (MIGRATION-PLAN.md), qris membawa salinan signature verifier & webhook
 * worker-nya SENDIRI, terpisah dari bank.
 *
 * <p>Bean di sini bernama sama dengan milik bank tapi berada di package berbeda; agar
 * tak ambigu saat main-app memuat keduanya, worker qris hanya menangani schema QRIS —
 * lihat filter di {@link #webhookWorker}.
 */
@Configuration
public class PlatformBeansConfig {

    /** Nama schema produk ini — dipakai memfilter agar worker tak menyentuh schema bank. */
    static final String QRIS_SCHEMA = "qris";

    @Bean
    public SignatureVerifier qrisSignatureVerifier() {
        return new SnapSignatureVerifier();
    }

    /**
     * Worker outbox QRIS. {@code List<SchemaTables>} bisa berisi SchemaTables dari produk
     * lain saat main-app memuat semuanya, jadi disaring ke schema QRIS saja — worker satu
     * produk tak boleh menyentuh outbox produk lain (isolasi per module).
     */
    @Bean
    public WebhookWorker qrisWebhookWorker(JdbcClient db, List<SchemaTables> schemas) {
        List<String> mine = schemas.stream()
                .map(SchemaTables::schema)
                .filter(QRIS_SCHEMA::equals)
                .toList();
        return new WebhookWorker(db, mine);
    }
}

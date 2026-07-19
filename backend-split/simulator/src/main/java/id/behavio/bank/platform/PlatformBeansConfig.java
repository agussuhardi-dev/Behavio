package id.behavio.bank.platform;

import id.behavio.bank.platform.core.port.SignatureVerifier;
import id.behavio.bank.platform.persistence.SchemaTables;
import id.behavio.bank.platform.signature.SnapSignatureVerifier;
import id.behavio.bank.platform.webhook.WebhookWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * Perakitan platform milik module BANK (dulu {@code app.config.CoreBeansConfig} yang
 * lintas-produk). Sejak pemisahan penuh (MIGRATION-PLAN.md), bank membawa salinan
 * signature verifier & webhook worker-nya SENDIRI — main-app tak lagi merakit ini.
 */
@Configuration
public class PlatformBeansConfig {

    /** Nama schema produk ini — dipakai memfilter agar worker tak menyentuh schema qris. */
    static final String BANK_SCHEMA = "bank";

    @Bean
    public SignatureVerifier bankSignatureVerifier() {
        return new SnapSignatureVerifier();
    }

    /**
     * Worker outbox BANK. {@code List<SchemaTables>} bisa berisi SchemaTables dari produk
     * lain saat main-app memuat semuanya, jadi disaring ke schema bank saja — worker satu
     * produk tak boleh menyentuh outbox produk lain (isolasi per module).
     */
    @Bean
    public WebhookWorker bankWebhookWorker(JdbcClient db, List<SchemaTables> schemas) {
        List<String> mine = schemas.stream()
                .map(SchemaTables::schema)
                .filter(BANK_SCHEMA::equals)
                .toList();
        return new WebhookWorker(db, mine);
    }
}

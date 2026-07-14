package id.behavio.app.config;

import id.behavio.core.port.SignatureVerifier;
import id.behavio.persistence.SchemaTables;
import id.behavio.signature.SnapSignatureVerifier;
import id.behavio.webhook.WebhookWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * Perakitan lintas-produk. Yang spesifik produk dirakit di modulnya masing-masing
 * ({@code BankProductConfig}, {@code QrisProductConfig}); di sini hanya yang benar-benar
 * dipakai bersama.
 */
@Configuration
public class CoreBeansConfig {

    @Bean
    public SignatureVerifier signatureVerifier() {
        return new SnapSignatureVerifier();
    }

    /**
     * Satu worker outbox untuk semua schema produk. Daftar schema diambil dari bean
     * SchemaTables yang didaftarkan tiap produk, jadi menambah produk baru tidak menuntut
     * perubahan di sini.
     */
    @Bean
    public WebhookWorker webhookWorker(JdbcClient db, List<SchemaTables> schemas) {
        return new WebhookWorker(db, schemas.stream().map(SchemaTables::schema).toList());
    }
}

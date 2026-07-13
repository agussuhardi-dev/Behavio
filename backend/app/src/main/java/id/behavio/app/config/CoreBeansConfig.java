package id.behavio.app.config;

import id.behavio.core.port.SignatureVerifier;
import id.behavio.signature.SnapSignatureVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring adapter yang berupa pure-Java (tanpa anotasi Spring) ke port core.
 * Di sinilah "perakitan" Hexagonal dilakukan.
 */
@Configuration
public class CoreBeansConfig {

    @Bean
    public SignatureVerifier signatureVerifier() {
        return new SnapSignatureVerifier();
    }
}

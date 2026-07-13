package id.behavio.core.port;

import id.behavio.core.domain.Partner;
import id.behavio.core.rule.Scenario;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: baca konfigurasi (cetak biru) — partner & scenario aktif per endpoint.
 * Implementasi (adapter-persistence) memuat dari DB; core cukup tahu kontraknya.
 */
public interface ConfigRepository {

    /** Partner dari header X-PARTNER-ID (isolasi tenant). */
    Optional<Partner> findPartner(UUID simulatorId, String partnerHeaderId);

    /** Scenario aktif untuk endpoint (method+path) pada simulator. */
    Optional<Scenario> findActiveScenario(UUID simulatorId, String method, String path);
}

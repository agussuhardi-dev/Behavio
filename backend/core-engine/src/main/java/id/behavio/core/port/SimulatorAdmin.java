package id.behavio.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port untuk Admin API: baca/ubah konfigurasi simulator (bukan state runtime).
 * Implementasi di adapter-persistence. Dipakai adapter-web (Admin controller).
 */
public interface SimulatorAdmin {

    List<SimulatorView> list();

    Optional<SimulatorView> find(UUID simulatorId);

    void setStatus(UUID simulatorId, String status);

    /** Ganti scenario aktif untuk endpoint transfer-intrabank (sakelar utama testing). */
    void setActiveScenario(UUID simulatorId, String scenarioName);

    record SimulatorView(UUID id, String name, int port, String status) {}
}

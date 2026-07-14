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

    /** Buat simulator (profil bank) baru dengan baseline SNAP (endpoint+scenario+partner+akun). */
    UUID create(String name, int port, String signatureMode);

    /** Duplikat profil bank: baseline + salin partner key, definisi custom scenario, & akun. */
    UUID cloneSimulator(UUID sourceId, String name, int port);

    /** Hapus simulator beserta seluruh config & state-nya. */
    void delete(UUID simulatorId);

    record SimulatorView(UUID id, String name, int port, String status) {}
}

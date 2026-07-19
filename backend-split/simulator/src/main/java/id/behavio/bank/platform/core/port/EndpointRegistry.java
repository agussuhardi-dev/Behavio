package id.behavio.bank.platform.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: full CRUD registry URL endpoint per-simulator — "Blueprint dapat
 * di-override penuh" (design.md §2).
 */
public interface EndpointRegistry {

    Optional<String> resolveOperation(UUID simulatorId, String method, String path);

    List<EndpointConfig> list(UUID simulatorId);

    /** Dapatkan detail satu endpoint (termasuk headers JSON). */
    Optional<EndpointDetail> getDetail(UUID simulatorId, String operation);

    void updatePath(UUID simulatorId, String operation, String newPath);

    void resetPath(UUID simulatorId, String operation);

    /** Tambah endpoint kustom (di luar katalog SnapOperations). Operation = null. */
    EndpointDetail addEndpoint(UUID simulatorId, String method, String path, String headers, String label);

    /** Hapus endpoint (termasuk scenario terkait). Operation "" = custom. */
    void deleteEndpoint(UUID simulatorId, String operation);

    /** Update method/headers/label endpoint. Tidak ganti path (pakai updatePath). */
    void updateEndpointMeta(UUID simulatorId, String operation, String method, String headers, String label);

    record EndpointConfig(String operation, String method, String path, String defaultPath,
                          String label, String headers) {}

    record EndpointDetail(String operation, String method, String path, String defaultPath,
                          String label, String headers, boolean hasScenario) {}
}

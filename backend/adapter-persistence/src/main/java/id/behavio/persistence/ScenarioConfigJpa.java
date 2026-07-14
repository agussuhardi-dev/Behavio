package id.behavio.persistence;

import id.behavio.core.port.ScenarioConfigPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi ScenarioConfigPort: baca/tulis definisi scenario (JSON) untuk endpoint
 * mana pun (generik lewat {@code product} → method+path, lihat {@link ProductEndpoints}).
 * Definisi custom disimpan di kolom scenarios.definition; bila kosong, dikembalikan
 * serialisasi preset blueprint sebagai titik awal edit.
 *
 * Termasuk lazy-provisioning: bila simulator belum punya endpoint QRIS (simulator lama
 * sebelum Fase 4), endpoint + scenario otomatis dibuat saat pertama kali diakses.
 */
@Repository
public class ScenarioConfigJpa implements ScenarioConfigPort {

    private final JdbcClient db;
    private final ScenarioCodec codec = new ScenarioCodec();

    private static final String[] QRIS_SCENARIO_NAMES = {"Normal", "Merchant Diblokir", "Service Down"};

    public ScenarioConfigJpa(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<String> scenarioNames(UUID simulatorId, String product) {
        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        ensureProvisioned(simulatorId, product, ep);
        return db.sql("""
                SELECT s.name FROM scenarios s
                JOIN endpoints e ON s.endpoint_id = e.id
                WHERE e.simulator_id = ? AND e.path = ? ORDER BY s.name
                """)
                .param(simulatorId).param(ep.path())
                .query(String.class).list();
    }

    @Override
    public Optional<String> activeScenarioName(UUID simulatorId, String product) {
        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        ensureProvisioned(simulatorId, product, ep);
        return db.sql("""
                SELECT s.name FROM scenarios s
                JOIN endpoints e ON e.id = s.endpoint_id
                WHERE e.simulator_id = ? AND e.path = ? AND e.active_scenario_id = s.id
                """)
                .param(simulatorId).param(ep.path())
                .query(String.class).optional();
    }

    @Override
    public String effectiveDefinition(UUID simulatorId, String product, String scenarioName) {
        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        ensureProvisioned(simulatorId, product, ep);
        Optional<String> custom = db.sql("""
                SELECT COALESCE(s.definition::text, '') FROM scenarios s
                JOIN endpoints e ON s.endpoint_id = e.id
                WHERE e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(simulatorId).param(ep.path()).param(scenarioName)
                .query(String.class).optional();
        if (custom.isPresent() && !custom.get().isBlank()) {
            return custom.get();
        }
        return codec.serialize(Blueprints.byName(product, scenarioName));
    }

    @Override
    public void saveDefinition(UUID simulatorId, String product, String scenarioName, String definitionJson) {
        codec.parse(scenarioName, definitionJson); // validasi sebelum simpan
        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        ensureProvisioned(simulatorId, product, ep);
        int updated = db.sql("""
                UPDATE scenarios s SET definition = ?::jsonb
                FROM endpoints e
                WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(definitionJson).param(simulatorId).param(ep.path()).param(scenarioName)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Scenario tidak ditemukan: " + scenarioName);
        }
    }

    @Override
    public void resetDefinition(UUID simulatorId, String product, String scenarioName) {
        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        ensureProvisioned(simulatorId, product, ep);
        db.sql("""
                UPDATE scenarios s SET definition = NULL
                FROM endpoints e
                WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(simulatorId).param(ep.path()).param(scenarioName)
                .update();
    }

    /**
     * Pastikan simulator punya endpoint + scenario baseline untuk product QRIS apa pun.
     * product="qris" → 3 scenario (Normal, Merchant Diblokir, Service Down).
     * product QRIS lain → 1 scenario (Normal).
     */
    private void ensureProvisioned(UUID simulatorId, String product, ProductEndpoints.Endpoint ep) {
        String p = product == null ? "" : product.trim().toLowerCase();
        if (!p.startsWith("qris")) return;

        boolean exists = db.sql("SELECT 1 FROM endpoints WHERE simulator_id = ? AND path = ?")
                .param(simulatorId).param(ep.path())
                .query(Integer.class).optional().isPresent();
        if (exists) return;

        UUID endpointId = UUID.randomUUID();
        String operation = p.equals("qris") ? "qris-generate" : p;
        db.sql("INSERT INTO endpoints (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                .param(endpointId).param(simulatorId).param(ep.method()).param(ep.path()).param(operation)
                .update();

        String[] names = p.equals("qris") ? QRIS_SCENARIO_NAMES : new String[]{"Normal"};
        UUID normalId = null;
        for (String scName : names) {
            UUID scId = UUID.randomUUID();
            if ("Normal".equals(scName)) normalId = scId;
            db.sql("INSERT INTO scenarios (id, endpoint_id, name) VALUES (?, ?, ?)")
                    .param(scId).param(endpointId).param(scName)
                    .update();
        }
        if (normalId != null) {
            db.sql("UPDATE endpoints SET active_scenario_id = ? WHERE id = ?")
                    .param(normalId).param(endpointId)
                    .update();
        }
    }
}

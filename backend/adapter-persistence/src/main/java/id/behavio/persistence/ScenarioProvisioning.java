package id.behavio.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Sumber kebenaran TUNGGAL untuk provisioning endpoint+scenario sebuah {@code product}.
 * Dipakai bersama oleh {@link ScenarioConfigJpa} (editor definisi) dan
 * {@link JpaSimulatorAdmin} (ganti scenario aktif) supaya keduanya tak lagi punya
 * versi logika sendiri-sendiri.
 *
 * Bersifat lazy & idempoten (self-healing): simulator lama yang belum punya endpoint
 * atau baris scenario QRIS akan dilengkapi saat pertama diakses.
 */
@Repository
class ScenarioProvisioning {

    private static final String[] QRIS_GENERATE_SCENARIOS = {"Normal", "Merchant Diblokir", "Service Down"};

    private final JdbcClient db;

    ScenarioProvisioning(JdbcClient db) {
        this.db = db;
    }

    /** Nama scenario preset untuk sebuah product. */
    static String[] scenarioNamesFor(String product) {
        String p = product == null ? "" : product.trim().toLowerCase();
        // Hanya generate yang punya beberapa skenario; endpoint QRIS lain cuma "Normal".
        return p.equals("qris") || p.equals("qris-generate") ? QRIS_GENERATE_SCENARIOS : new String[]{"Normal"};
    }

    /**
     * Pastikan endpoint product ada DAN punya baris scenario + scenario aktif.
     * Hanya untuk product QRIS; transfer sudah di-provision penuh oleh provisionBaseline.
     */
    void ensure(UUID simulatorId, String product) {
        String p = product == null ? "" : product.trim().toLowerCase();
        if (!p.startsWith("qris")) return;

        ProductEndpoints.Endpoint ep = ProductEndpoints.resolve(product);
        UUID endpointId = db.sql("SELECT id FROM endpoints WHERE simulator_id = ? AND path = ?")
                .param(simulatorId).param(ep.path())
                .query(UUID.class).optional()
                .orElseGet(() -> insertEndpoint(simulatorId, p, ep));

        // Endpoint bisa saja SUDAH ada tapi tanpa baris scenario — provisionBaseline membuat
        // endpoint QRIS non-generate tanpa scenario. Lengkapi yang kurang, jangan berhenti di sini.
        ensureScenarios(endpointId, p);
    }

    private UUID insertEndpoint(UUID simulatorId, String p, ProductEndpoints.Endpoint ep) {
        UUID id = UUID.randomUUID();
        String operation = p.equals("qris") ? "qris-generate" : p;
        db.sql("INSERT INTO endpoints (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                .param(id).param(simulatorId).param(ep.method()).param(ep.path()).param(operation)
                .update();
        return id;
    }

    private void ensureScenarios(UUID endpointId, String product) {
        List<String> existing = db.sql("SELECT name FROM scenarios WHERE endpoint_id = ?")
                .param(endpointId)
                .query(String.class).list();

        for (String name : scenarioNamesFor(product)) {
            if (existing.stream().anyMatch(e -> e.equalsIgnoreCase(name))) continue;
            db.sql("INSERT INTO scenarios (id, endpoint_id, name) VALUES (?, ?, ?)")
                    .param(UUID.randomUUID()).param(endpointId).param(name)
                    .update();
        }

        // Tanpa scenario aktif, findActiveScenario tak menemukan override yang tersimpan.
        db.sql("""
                UPDATE endpoints SET active_scenario_id =
                    (SELECT id FROM scenarios WHERE endpoint_id = ? AND lower(name) = 'normal' LIMIT 1)
                WHERE id = ? AND active_scenario_id IS NULL
                """)
                .param(endpointId).param(endpointId)
                .update();
    }
}

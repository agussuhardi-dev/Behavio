package id.behavio.bank.platform.persistence;

import id.behavio.bank.platform.core.product.Operation;
import id.behavio.bank.platform.core.product.ProductCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * Sumber kebenaran TUNGGAL untuk provisioning endpoint+scenario sebuah produk — dipakai
 * saat membuat simulator baru maupun sebagai self-healing lazy saat endpoint diakses.
 *
 * Daftar operasi & nama scenario-nya diambil dari {@link ProductCatalog}; kelas ini tak
 * mengenal "transfer" atau "qris" sama sekali. Idempoten: aman dipanggil berulang.
 */
public class SchemaProvisioning {

    private final JdbcClient db;
    private final SchemaTables t;
    private final ProductCatalog catalog;

    public SchemaProvisioning(JdbcClient db, SchemaTables tables, ProductCatalog catalog) {
        this.db = db;
        this.t = tables;
        this.catalog = catalog;
    }

    /** Provision SELURUH operasi katalog untuk simulator (dipakai saat create/clone). */
    public void provisionAll(UUID simulatorId) {
        for (Operation op : catalog.operations()) {
            ensure(simulatorId, op);
        }
    }

    /** Pastikan satu operasi punya baris endpoint, baris scenario, dan scenario aktif. */
    public void ensure(UUID simulatorId, String operationKey) {
        catalog.byKey(operationKey).ifPresent(op -> ensure(simulatorId, op));
    }

    public void ensure(UUID simulatorId, Operation op) {
        UUID endpointId = db.sql("SELECT id FROM " + t.endpoints() + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(op.key())
                .query(UUID.class).optional()
                .orElseGet(() -> insertEndpoint(simulatorId, op));

        if (op.hasScenarios()) {
            ensureScenarios(endpointId, op);
        }
    }

    private UUID insertEndpoint(UUID simulatorId, Operation op) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO " + t.endpoints() + " (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                .param(id).param(simulatorId).param(op.method()).param(op.defaultPath()).param(op.key())
                .update();
        return id;
    }

    private void ensureScenarios(UUID endpointId, Operation op) {
        List<String> existing = db.sql("SELECT name FROM " + t.scenarios() + " WHERE endpoint_id = ?")
                .param(endpointId)
                .query(String.class).list();

        for (String name : op.scenarioNames()) {
            if (existing.stream().anyMatch(e -> e.equalsIgnoreCase(name))) continue;
            db.sql("INSERT INTO " + t.scenarios() + " (id, endpoint_id, name) VALUES (?, ?, ?)")
                    .param(UUID.randomUUID()).param(endpointId).param(name)
                    .update();
        }

        // Tanpa scenario aktif, findActiveScenario tak menemukan override yang tersimpan.
        // Default = scenario pertama di katalog (konvensi: "Normal").
        String first = op.scenarioNames().get(0);
        db.sql("UPDATE " + t.endpoints() + " SET active_scenario_id = "
                + "(SELECT id FROM " + t.scenarios() + " WHERE endpoint_id = ? AND lower(name) = lower(?) LIMIT 1) "
                + "WHERE id = ? AND active_scenario_id IS NULL")
                .param(endpointId).param(first).param(endpointId)
                .update();
    }
}

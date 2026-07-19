package id.behavio.qris.platform.persistence;

import id.behavio.qris.platform.core.port.SimulatorAdmin;
import id.behavio.qris.platform.core.product.Operation;
import id.behavio.qris.platform.core.product.ProductCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SimulatorAdmin} untuk satu schema produk — CRUD profil + clone + sakelar
 * scenario aktif. Ditulis sekali, di-instansiasi per produk.
 *
 * Berbasis JdbcClient (bukan JPA): tabel di schema {@code bank} dan {@code qris}
 * strukturnya identik, sehingga schema cukup jadi parameter. Dengan JPA, {@code @Table}
 * yang statis akan memaksa entity class diduplikasi per schema dan mesin ini ikut
 * menjadi dua salinan yang harus dirawat paralel.
 */
public class SchemaSimulatorAdmin implements SimulatorAdmin {

    private static final String DEFAULT_PARTNER = "PARTNER001";
    private static final String DEFAULT_SECRET = "secret123";

    private final JdbcClient db;
    private final SchemaTables t;
    private final ProductCatalog catalog;
    private final SchemaProvisioning provisioning;
    private final PortRegistry ports;
    private final BaselineExtension baseline;

    public SchemaSimulatorAdmin(JdbcClient db, SchemaTables tables, ProductCatalog catalog,
                                SchemaProvisioning provisioning, PortRegistry ports,
                                BaselineExtension baseline) {
        this.db = db;
        this.t = tables;
        this.catalog = catalog;
        this.provisioning = provisioning;
        this.ports = ports;
        this.baseline = baseline == null ? BaselineExtension.NONE : baseline;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SimulatorView> list() {
        return db.sql("SELECT id, name, port, status FROM " + t.simulators() + " ORDER BY name")
                .query((rs, n) -> new SimulatorView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("port"), rs.getString("status")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SimulatorView> find(UUID simulatorId) {
        return db.sql("SELECT id, name, port, status FROM " + t.simulators() + " WHERE id = ?")
                .param(simulatorId)
                .query((rs, n) -> new SimulatorView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("port"), rs.getString("status")))
                .optional();
    }

    @Override
    @Transactional
    public void setStatus(UUID simulatorId, String status) {
        db.sql("UPDATE " + t.simulators() + " SET status = ? WHERE id = ?")
                .param(status).param(simulatorId).update();
    }

    @Override
    @Transactional
    public void setActiveScenario(UUID simulatorId, String operationKey, String scenarioName) {
        provisioning.ensure(simulatorId, operationKey); // self-healing untuk profil lama

        UUID endpointId = db.sql("SELECT id FROM " + t.endpoints() + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(resolveOperation(simulatorId, operationKey))
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Operasi '" + operationKey + "' tidak ada untuk simulator " + simulatorId));

        UUID scenarioId = db.sql("SELECT id FROM " + t.scenarios()
                        + " WHERE endpoint_id = ? AND lower(name) = lower(?)")
                .param(endpointId).param(scenarioName.trim())
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Scenario '" + scenarioName + "' tidak ada"));

        db.sql("UPDATE " + t.endpoints() + " SET active_scenario_id = ? WHERE id = ?")
                .param(scenarioId).param(endpointId).update();
    }

    @Override
    @Transactional
    public UUID create(String name, int port, String signatureMode) {
        return provisionBaseline(name, port, signatureMode);
    }

    @Override
    @Transactional
    public UUID cloneSimulator(UUID sourceId, String name, int port) {
        String sigMode = db.sql("SELECT signature_mode FROM " + t.simulators() + " WHERE id = ?")
                .param(sourceId)
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Simulator sumber tidak ditemukan"));

        UUID newId = provisionBaseline(name, port, sigMode);

        // Kunci partner (public key RSA / client secret HMAC)
        db.sql("UPDATE " + t.partners() + " np SET public_key = sp.public_key, client_secret = sp.client_secret "
                        + "FROM " + t.partners() + " sp WHERE np.simulator_id = ? AND sp.simulator_id = ? "
                        + "AND np.partner_id = sp.partner_id")
                .param(newId).param(sourceId).update();

        // Definisi custom scenario (hasil editor). Dicocokkan lewat `operation` — bukan
        // `path` seperti sebelumnya, karena path justru boleh berbeda per-simulator
        // (design.md §2 override), sehingga pencocokan by-path diam-diam gagal menyalin
        // override milik simulator yang path-nya sudah di-custom.
        db.sql("UPDATE " + t.scenarios() + " ns SET definition = ss.definition "
                        + "FROM " + t.scenarios() + " ss "
                        + "JOIN " + t.endpoints() + " se ON ss.endpoint_id = se.id "
                        + "JOIN " + t.endpoints() + " ne ON ne.simulator_id = ? AND ne.operation = se.operation "
                        + "WHERE ns.endpoint_id = ne.id AND se.simulator_id = ? AND ss.name = ns.name "
                        + "AND ss.definition IS NOT NULL")
                .param(newId).param(sourceId).update();

        // Path endpoint yang di-custom ikut dibawa — kalau tidak, clone "profil BRI"
        // diam-diam kembali ke path ASPI standar.
        db.sql("UPDATE " + t.endpoints() + " ne SET path = se.path, headers = se.headers "
                        + "FROM " + t.endpoints() + " se "
                        + "WHERE ne.simulator_id = ? AND se.simulator_id = ? AND ne.operation = se.operation")
                .param(newId).param(sourceId).update();

        UUID newPartnerId = defaultPartnerId(newId);
        baseline.afterClone(sourceId, newId, newPartnerId);
        return newId;
    }

    @Override
    @Transactional
    public void delete(UUID simulatorId) {
        ports.release(simulatorId);
        db.sql("DELETE FROM " + t.simulators() + " WHERE id = ?")
                .param(simulatorId).update();   // FK ON DELETE CASCADE membawa config + state
    }

    /** Simulator baru + partner default + seluruh endpoint/scenario katalog produk. */
    private UUID provisionBaseline(String name, int port, String signatureMode) {
        UUID simId = UUID.randomUUID();
        // Klaim port DULU: gagal di sini (port dipakai produk lain) harus membatalkan
        // seluruh transaksi sebelum baris apa pun tertulis.
        ports.claim(catalog.key(), simId, port);

        db.sql("INSERT INTO " + t.simulators() + " (id, name, port, status, signature_mode) "
                        + "VALUES (?, ?, ?, 'STOPPED', ?)")
                .param(simId).param(name).param(port)
                .param(signatureMode == null ? "SIMULATED" : signatureMode)
                .update();

        UUID partnerId = UUID.randomUUID();
        db.sql("INSERT INTO " + t.partners() + " (id, simulator_id, partner_id, client_secret) VALUES (?, ?, ?, ?)")
                .param(partnerId).param(simId).param(DEFAULT_PARTNER).param(DEFAULT_SECRET)
                .update();

        provisioning.provisionAll(simId);
        baseline.afterProvision(simId, partnerId);
        return simId;
    }

    private UUID defaultPartnerId(UUID simulatorId) {
        return db.sql("SELECT id FROM " + t.partners() + " WHERE simulator_id = ? ORDER BY created_at LIMIT 1")
                .param(simulatorId)
                .query(UUID.class).single();
    }

    /** Terima alias lama ("qris" → "qris-generate") agar URL dashboard yang beredar tak putus. */
    private String resolveOperation(UUID simulatorId, String operationKey) {
        return catalog.byKey(operationKey)
                .map(Operation::key)
                .orElseGet(() -> {
                    Long count = db.sql("SELECT count(*) FROM " + t.endpoints()
                                    + " WHERE simulator_id = ? AND operation = ?")
                            .param(simulatorId).param(operationKey).query(Long.class).single();
                    if (count != null && count > 0) return operationKey;
                    throw new IllegalArgumentException("Operasi tak dikenal: " + operationKey);
                });
    }
}

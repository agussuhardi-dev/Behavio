package id.behavio.bank.platform.persistence;

import id.behavio.bank.platform.core.port.ScenarioConfigPort;
import id.behavio.bank.platform.core.product.Operation;
import id.behavio.bank.platform.core.product.ProductCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ScenarioConfigPort} untuk satu schema produk: baca/tulis definisi scenario (JSON)
 * agar dapat di-edit dari dashboard (design.md §2 override, §8 rule).
 *
 * Endpoint dicari lewat kolom {@code operation}, bukan {@code path}. Sebelum pemisahan
 * lookup dilakukan by-path memakai konstanta blueprint, sehingga begitu path di-custom
 * (fitur yang justru sengaja ada) editor scenario langsung 404.
 */
public class SchemaScenarioConfig implements ScenarioConfigPort {

    private final JdbcClient db;
    private final SchemaTables t;
    private final ProductCatalog catalog;
    private final SchemaProvisioning provisioning;
    private final ScenarioCodec codec;

    public SchemaScenarioConfig(JdbcClient db, SchemaTables tables, ProductCatalog catalog,
                                SchemaProvisioning provisioning) {
        this.db = db;
        this.t = tables;
        this.catalog = catalog;
        this.provisioning = provisioning;
        this.codec = new ScenarioCodec(catalog.actionCodec());
    }

    @Override
    @Transactional
    public List<String> scenarioNames(UUID simulatorId, String product) {
        Operation op = catalog.byKey(product).orElse(null);
        if (op != null) {
            provisioning.ensure(simulatorId, op);
        }
        return db.sql("SELECT s.name FROM " + t.scenarios() + " s JOIN " + t.endpoints() + " e "
                        + "ON s.endpoint_id = e.id WHERE e.simulator_id = ? AND e.operation = ? ORDER BY s.name")
                .param(simulatorId).param(product)
                .query(String.class).list();
    }

    @Override
    @Transactional
    public Optional<String> activeScenarioName(UUID simulatorId, String product) {
        Operation op = catalog.byKey(product).orElse(null);
        if (op != null) {
            provisioning.ensure(simulatorId, op);
        }
        return db.sql("SELECT s.name FROM " + t.scenarios() + " s JOIN " + t.endpoints() + " e "
                        + "ON e.id = s.endpoint_id WHERE e.simulator_id = ? AND e.operation = ? "
                        + "AND e.active_scenario_id = s.id")
                .param(simulatorId).param(product)
                .query(String.class).optional();
    }

    @Override
    @Transactional
    public String effectiveDefinition(UUID simulatorId, String product, String scenarioName) {
        Operation op = catalog.byKey(product).orElse(null);
        if (op != null) {
            provisioning.ensure(simulatorId, op);
        }
        Optional<String> custom = db.sql("SELECT COALESCE(s.definition::text, '') FROM " + t.scenarios() + " s "
                        + "JOIN " + t.endpoints() + " e ON s.endpoint_id = e.id "
                        + "WHERE e.simulator_id = ? AND e.operation = ? AND s.name = ?")
                .param(simulatorId).param(product).param(scenarioName)
                .query(String.class).optional();
        if (custom.isPresent() && !custom.get().isBlank()) {
            return custom.get();
        }
        if (op != null) {
            return codec.serialize(catalog.blueprint(op.key(), scenarioName).orElseThrow(() ->
                    new IllegalArgumentException("Tak ada preset untuk '" + op.key() + "' scenario '" + scenarioName + "'")));
        }
        return "{\n  \"fallback\": {\n    \"actions\": [],\n    \"response\": {\n      \"httpStatus\": 200,\n      \"responseCode\": \"2005700\",\n      \"responseMessage\": \"Successful\",\n      \"body\": {}\n    }\n  }\n}";
    }

    @Override
    @Transactional
    public void saveDefinition(UUID simulatorId, String product, String scenarioName, String definitionJson) {
        codec.parse(scenarioName, definitionJson);
        Operation op = catalog.byKey(product).orElse(null);
        if (op != null) {
            provisioning.ensure(simulatorId, op);
        }
        int updated = db.sql("UPDATE " + t.scenarios() + " s SET definition = ?::jsonb FROM " + t.endpoints() + " e "
                        + "WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.operation = ? AND s.name = ?")
                .param(definitionJson).param(simulatorId).param(product).param(scenarioName)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Scenario tidak ditemukan: " + scenarioName);
        }
    }

    @Override
    @Transactional
    public void resetDefinition(UUID simulatorId, String product, String scenarioName) {
        Operation op = catalog.byKey(product).orElse(null);
        if (op != null) {
            provisioning.ensure(simulatorId, op);
        }
        int updated = db.sql("UPDATE " + t.scenarios() + " s SET definition = NULL FROM " + t.endpoints() + " e "
                        + "WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.operation = ? AND s.name = ?")
                .param(simulatorId).param(product).param(scenarioName)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Scenario tidak ditemukan: " + scenarioName);
        }
    }
}

package id.behavio.persistence;

import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.product.ProductCatalog;
import id.behavio.core.rule.Scenario;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link ConfigRepository} untuk satu schema produk. Scenario aktif = definisi custom
 * (kolom {@code scenarios.definition}, hasil editor dashboard) bila ada; bila kosong,
 * jatuh ke preset blueprint dari {@link ProductCatalog} (design.md §2 & §8).
 *
 * Produk ditentukan oleh instance ini (satu instance = satu schema), bukan ditebak dari
 * path seperti sebelum pemisahan — dulu {@code product} diturunkan dengan membandingkan
 * path terhadap konstanta blueprint, yang langsung salah begitu path di-custom user.
 */
public class SchemaConfigRepository implements ConfigRepository {

    private final JdbcClient db;
    private final SchemaTables t;
    private final ProductCatalog catalog;
    private final ScenarioCodec codec;

    public SchemaConfigRepository(JdbcClient db, SchemaTables tables, ProductCatalog catalog) {
        this.db = db;
        this.t = tables;
        this.catalog = catalog;
        this.codec = new ScenarioCodec(catalog.actionCodec());
    }

    @Override
    public Optional<Partner> findPartner(UUID simulatorId, String partnerHeaderId) {
        return db.sql("SELECT id, public_key, client_secret FROM " + t.partners()
                        + " WHERE simulator_id = ? AND partner_id = ?")
                .param(simulatorId).param(partnerHeaderId)
                .query((rs, n) -> new Partner(rs.getObject("id", UUID.class), simulatorId, partnerHeaderId,
                        rs.getString("public_key"), rs.getString("client_secret")))
                .optional();
    }

    @Override
    public Optional<Scenario> findActiveScenario(UUID simulatorId, String method, String path) {
        Optional<Endpoint> ep = db.sql("SELECT operation, active_scenario_id FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND method = ? AND path = ?")
                .param(simulatorId).param(method).param(path)
                .query((rs, n) -> new Endpoint(rs.getString("operation"),
                        rs.getObject("active_scenario_id", UUID.class)))
                .optional();
        if (ep.isEmpty() || ep.get().operation() == null) {
            return Optional.empty();
        }
        String operation = ep.get().operation();

        if (ep.get().activeScenarioId() == null) {
            return customOrDefaultBlueprint(operation, "Normal");
        }

        Optional<ActiveScenario> active = db.sql("SELECT name, COALESCE(definition::text, '') AS def FROM "
                        + t.scenarios() + " WHERE id = ?")
                .param(ep.get().activeScenarioId())
                .query((rs, n) -> new ActiveScenario(rs.getString("name"), rs.getString("def")))
                .optional();
        if (active.isEmpty()) {
            return customOrDefaultBlueprint(operation, "Normal");
        }
        ActiveScenario sc = active.get();
        return sc.definition().isBlank()
                ? customOrDefaultBlueprint(operation, sc.name())
                : Optional.of(codec.parse(sc.name(), sc.definition()));
    }

    private Optional<Scenario> customOrDefaultBlueprint(String operation, String scenarioName) {
        Optional<Scenario> bp = catalog.blueprint(operation, scenarioName);
        if (bp.isPresent()) return bp;
        if (operation.startsWith("custom-")) {
            return Optional.of(codec.parse("Normal",
                    "{\"fallback\":{\"actions\":[],\"response\":{\"httpStatus\":200,\"responseCode\":\"2005700\",\"responseMessage\":\"Successful\",\"body\":{}}}}"));
        }
        return Optional.empty();
    }

    @Override
    public SignatureMode signatureMode(UUID simulatorId) {
        return db.sql("SELECT signature_mode FROM " + t.simulators() + " WHERE id = ?")
                .param(simulatorId)
                .query(String.class).optional()
                .map(SignatureMode::valueOf)
                .orElse(SignatureMode.SIMULATED);
    }

    private record Endpoint(String operation, UUID activeScenarioId) {}

    private record ActiveScenario(String name, String definition) {}
}

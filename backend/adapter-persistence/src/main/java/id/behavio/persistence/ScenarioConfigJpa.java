package id.behavio.persistence;

import id.behavio.core.port.ScenarioConfigPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi ScenarioConfigPort: baca/tulis definisi scenario (JSON) untuk endpoint
 * transfer-intrabank. Definisi custom disimpan di kolom scenarios.definition; bila
 * kosong, dikembalikan serialisasi preset blueprint sebagai titik awal edit.
 */
@Repository
public class ScenarioConfigJpa implements ScenarioConfigPort {

    private static final String PATH = "/v1.0/transfer-intrabank";

    private final JdbcClient db;
    private final ScenarioCodec codec = new ScenarioCodec();

    public ScenarioConfigJpa(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<String> scenarioNames(UUID simulatorId) {
        return db.sql("""
                SELECT s.name FROM scenarios s
                JOIN endpoints e ON s.endpoint_id = e.id
                WHERE e.simulator_id = ? AND e.path = ? ORDER BY s.name
                """)
                .param(simulatorId).param(PATH)
                .query(String.class).list();
    }

    @Override
    public String effectiveDefinition(UUID simulatorId, String scenarioName) {
        Optional<String> custom = db.sql("""
                SELECT COALESCE(s.definition::text, '') FROM scenarios s
                JOIN endpoints e ON s.endpoint_id = e.id
                WHERE e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(simulatorId).param(PATH).param(scenarioName)
                .query(String.class).optional();
        if (custom.isPresent() && !custom.get().isBlank()) {
            return custom.get();
        }
        return codec.serialize(Blueprints.byName(scenarioName));
    }

    @Override
    public void saveDefinition(UUID simulatorId, String scenarioName, String definitionJson) {
        codec.parse(scenarioName, definitionJson); // validasi sebelum simpan
        int updated = db.sql("""
                UPDATE scenarios s SET definition = ?::jsonb
                FROM endpoints e
                WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(definitionJson).param(simulatorId).param(PATH).param(scenarioName)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Scenario tidak ditemukan: " + scenarioName);
        }
    }

    @Override
    public void resetDefinition(UUID simulatorId, String scenarioName) {
        db.sql("""
                UPDATE scenarios s SET definition = NULL
                FROM endpoints e
                WHERE s.endpoint_id = e.id AND e.simulator_id = ? AND e.path = ? AND s.name = ?
                """)
                .param(simulatorId).param(PATH).param(scenarioName)
                .update();
    }
}

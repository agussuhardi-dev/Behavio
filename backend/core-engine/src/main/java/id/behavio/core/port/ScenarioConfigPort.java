package id.behavio.core.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port untuk Admin: baca/tulis definisi scenario (request cond + response)
 * sebagai JSON, agar dapat di-edit dari dashboard (design.md §2 override, §8 rule).
 */
public interface ScenarioConfigPort {

    /** Nama semua scenario untuk endpoint transfer pada simulator. */
    List<String> scenarioNames(UUID simulatorId);

    /**
     * Definisi efektif scenario sebagai JSON: definisi custom bila ada di DB,
     * selain itu serialisasi preset blueprint (sebagai titik awal edit).
     */
    String effectiveDefinition(UUID simulatorId, String scenarioName);

    /** Simpan definisi custom (JSON) untuk scenario. */
    void saveDefinition(UUID simulatorId, String scenarioName, String definitionJson);

    /** Kembalikan scenario ke preset (hapus override). */
    void resetDefinition(UUID simulatorId, String scenarioName);
}

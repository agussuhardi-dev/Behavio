package id.behavio.qris.platform.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port untuk Admin: baca/tulis definisi scenario (request cond + response)
 * sebagai JSON, agar dapat di-edit dari dashboard (design.md §2 override, §8 rule).
 * {@code product} membedakan endpoint mana yang diedit (mis. "transfer", "qris") —
 * generik lintas endpoint, bukan hanya Transfer Intrabank.
 */
public interface ScenarioConfigPort {

    /** Nama semua scenario untuk endpoint {@code product} pada simulator. */
    List<String> scenarioNames(UUID simulatorId, String product);

    /** Nama scenario yang SEDANG aktif untuk endpoint {@code product} — agar dashboard sinkron. */
    Optional<String> activeScenarioName(UUID simulatorId, String product);

    /**
     * Definisi efektif scenario sebagai JSON: definisi custom bila ada di DB,
     * selain itu serialisasi preset blueprint (sebagai titik awal edit).
     */
    String effectiveDefinition(UUID simulatorId, String product, String scenarioName);

    /** Simpan definisi custom (JSON) untuk scenario. */
    void saveDefinition(UUID simulatorId, String product, String scenarioName, String definitionJson);

    /** Kembalikan scenario ke preset (hapus override). */
    void resetDefinition(UUID simulatorId, String product, String scenarioName);
}

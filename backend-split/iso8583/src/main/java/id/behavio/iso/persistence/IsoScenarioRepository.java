package id.behavio.iso.persistence;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.scenario.IsoScenario;
import id.behavio.iso.scenario.IsoScenarioCatalog;
import id.behavio.iso.scenario.IsoScenarioCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Penyimpanan scenario ISO per (simulator, operasi).
 *
 * <p>Baris scenario dibuat <b>saat dibutuhkan</b> ({@link #ensureProvisioned}), bukan saat
 * simulator dibuat: daftar operasi berasal dari profil spec yang bisa berbeda per
 * simulator, jadi tak ada satu daftar tetap yang bisa di-seed di muka.
 */
@Repository
public class IsoScenarioRepository {

    private final JdbcClient db;

    public IsoScenarioRepository(JdbcClient db) {
        this.db = db;
    }

    /** Pastikan seluruh scenario bawaan ada untuk operasi ini, dan satu di antaranya aktif. */
    @Transactional
    public void ensureProvisioned(UUID simulatorId, String operation) {
        int existing = db.sql("SELECT count(*) FROM iso8583.scenarios WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation)
                .query(Integer.class).single();
        if (existing == 0) {
            for (IsoScenario s : IsoScenarioCatalog.defaults()) {
                db.sql("""
                        INSERT INTO iso8583.scenarios (id, simulator_id, operation, name, definition, is_active)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?)
                        """)
                        .param(UUID.randomUUID()).param(simulatorId).param(operation)
                        .param(s.name()).param(IsoScenarioCodec.write(s))
                        .param(IsoScenarioCatalog.NORMAL.equals(s.name()))
                        .update();
            }
        }
    }

    /** Scenario aktif; {@code Normal} bila belum ada apa-apa (mis. operasi baru). */
    public IsoScenario active(UUID simulatorId, String operation) {
        return db.sql("""
                SELECT definition::text FROM iso8583.scenarios
                WHERE simulator_id = ? AND operation = ? AND is_active LIMIT 1
                """)
                .param(simulatorId).param(operation)
                .query(String.class).optional()
                .map(IsoScenarioCodec::read)
                .orElseGet(IsoScenario::normal);
    }

    public List<String> names(UUID simulatorId, String operation) {
        ensureProvisioned(simulatorId, operation);
        return db.sql("SELECT name FROM iso8583.scenarios WHERE simulator_id = ? AND operation = ? ORDER BY name")
                .param(simulatorId).param(operation)
                .query(String.class).list();
    }

    public Optional<String> activeName(UUID simulatorId, String operation) {
        return db.sql("""
                SELECT name FROM iso8583.scenarios
                WHERE simulator_id = ? AND operation = ? AND is_active LIMIT 1
                """)
                .param(simulatorId).param(operation).query(String.class).optional();
    }

    public Optional<String> definition(UUID simulatorId, String operation, String name) {
        ensureProvisioned(simulatorId, operation);
        return db.sql("""
                SELECT definition::text FROM iso8583.scenarios
                WHERE simulator_id = ? AND operation = ? AND name = ?
                """)
                .param(simulatorId).param(operation).param(name)
                .query(String.class).optional();
    }

    @Transactional
    public void setActive(UUID simulatorId, String operation, String name) {
        ensureProvisioned(simulatorId, operation);
        int found = db.sql("SELECT count(*) FROM iso8583.scenarios WHERE simulator_id = ? AND operation = ? AND name = ?")
                .param(simulatorId).param(operation).param(name)
                .query(Integer.class).single();
        if (found == 0) {
            throw new IsoCodecException("Scenario '" + name + "' tidak ada untuk operasi '" + operation + "'");
        }
        db.sql("UPDATE iso8583.scenarios SET is_active = FALSE WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation).update();
        db.sql("UPDATE iso8583.scenarios SET is_active = TRUE WHERE simulator_id = ? AND operation = ? AND name = ?")
                .param(simulatorId).param(operation).param(name).update();
    }

    /** Simpan definisi custom — inilah "Edit Response" untuk ISO. */
    @Transactional
    public void saveDefinition(UUID simulatorId, String operation, String name, String json) {
        IsoScenarioCodec.read(json);   // validasi SEKARANG, bukan saat pesan tiba
        ensureProvisioned(simulatorId, operation);
        int n = db.sql("""
                UPDATE iso8583.scenarios SET definition = ?::jsonb
                WHERE simulator_id = ? AND operation = ? AND name = ?
                """)
                .param(json).param(simulatorId).param(operation).param(name)
                .update();
        if (n == 0) {
            throw new IsoCodecException("Scenario '" + name + "' tidak ada untuk operasi '" + operation + "'");
        }
    }

    /** Kembalikan ke cetak biru bawaan. */
    @Transactional
    public void resetDefinition(UUID simulatorId, String operation, String name) {
        saveDefinition(simulatorId, operation, name,
                IsoScenarioCodec.write(IsoScenarioCatalog.byName(name)));
    }
}

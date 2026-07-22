package id.behavio.iso.persistence;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.spec.SpecProfile;
import id.behavio.iso.spec.SpecProfileJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Penyimpanan profil spec ISO-8583.
 *
 * <p><b>IMMUTABLE:</b> tak ada operasi update — unggahan berikutnya membuat versi BARU.
 * Simulator tetap menunjuk versi yang sudah diuji; tanpa itu, tes yang kemarin hijau bisa
 * mendadak merah tanpa jejak apa yang berubah ({@code docs/iso8583-plan.md} §2 aturan 3).
 *
 * <p><b>Catatan jsonb:</b> kolom {@code definition} di-cast EKSPLISIT ({@code ?::jsonb}
 * saat tulis, {@code definition::text} saat baca). Pelajaran mahal dari produk lain:
 * binding otomatis membuat driver mengirimnya sebagai {@code varchar} dan Postgres menolak
 * dengan SQLState 42804.
 */
@Repository
public class SpecProfileRepository {

    private final JdbcClient db;

    public SpecProfileRepository(JdbcClient db) {
        this.db = db;
    }

    /** Ringkasan untuk daftar — tanpa memuat definisi penuh. */
    public record Summary(UUID id, String name, String version, String parent,
                          String sourceFormat, Instant createdAt) {}

    @Transactional
    public UUID save(SpecProfile profile, String sourceFormat) {
        if (exists(profile.name(), profile.version())) {
            throw new IsoCodecException("Profil '" + profile.name() + "' versi '"
                    + profile.version() + "' sudah ada. Profil bersifat immutable — "
                    + "unggah dengan versi baru, jangan menimpa yang sudah diuji.");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO iso8583.spec_profiles (id, name, version, parent, definition, source_format)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """)
                .param(id).param(profile.name()).param(profile.version()).param(profile.parent())
                .param(SpecProfileJson.write(profile)).param(sourceFormat)
                .update();
        return id;
    }

    public boolean exists(String name, String version) {
        return db.sql("SELECT count(*) FROM iso8583.spec_profiles WHERE name = ? AND version = ?")
                .param(name).param(version)
                .query(Integer.class).single() > 0;
    }

    public List<Summary> list() {
        return db.sql("""
                SELECT id, name, version, parent, source_format, created_at
                FROM iso8583.spec_profiles ORDER BY name, created_at DESC
                """)
                .query((rs, n) -> new Summary(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("version"), rs.getString("parent"),
                        rs.getString("source_format"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<SpecProfile> find(String name, String version) {
        return db.sql("SELECT definition::text FROM iso8583.spec_profiles WHERE name = ? AND version = ?")
                .param(name).param(version)
                .query(String.class).optional()
                .map(SpecProfileJson::read);
    }

    /**
     * Versi TERBARU dari sebuah nama — dipakai menyelesaikan {@code extends}, karena
     * profil turunan merujuk induk lewat nama saja.
     */
    public Optional<SpecProfile> findLatest(String name) {
        return db.sql("""
                SELECT definition::text FROM iso8583.spec_profiles
                WHERE name = ? ORDER BY created_at DESC LIMIT 1
                """)
                .param(name)
                .query(String.class).optional()
                .map(SpecProfileJson::read);
    }

    /**
     * Siapa saja yang masih memakai profil ini — dipakai sebelum menghapus.
     *
     * @return nama simulator yang menunjuk versi ini, plus profil turunan yang
     *         {@code extends} nama ini. Kosong = aman dihapus.
     */
    public List<String> dependents(String name, String version) {
        List<String> out = new ArrayList<>(db.sql("""
                SELECT 'simulator ' || name FROM iso8583.simulators
                WHERE spec_profile_name = ? AND spec_profile_version = ?
                """).param(name).param(version).query(String.class).list());
        // Turunan menunjuk NAMA induk (bukan versi), jadi ia baru benar-benar kehilangan
        // induknya kalau versi TERAKHIR dari nama itu yang dihapus.
        boolean lastVersion = db.sql("SELECT count(*) FROM iso8583.spec_profiles WHERE name = ?")
                .param(name).query(Long.class).single() <= 1;
        if (lastVersion) {
            out.addAll(db.sql("""
                    SELECT 'profil turunan ' || name || ' v' || version
                    FROM iso8583.spec_profiles WHERE parent = ?
                    """).param(name).query(String.class).list());
        }
        return out;
    }

    /**
     * Menghapus SATU versi profil.
     *
     * <p>Tidak bertentangan dengan sifat immutable: yang dilarang adalah <i>mengubah</i>
     * profil yang sedang dipakai — perilaku simulator berubah diam-diam. Menghapus profil
     * yang tak dipakai siapa pun tak mengubah perilaku apa pun, dan tanpa ini unggahan
     * percobaan menumpuk selamanya.
     */
    public boolean delete(String name, String version) {
        return db.sql("DELETE FROM iso8583.spec_profiles WHERE name = ? AND version = ?")
                .param(name).param(version).update() > 0;
    }

    /** Semua nama profil beserta versinya — untuk dashboard nanti. */
    public Map<String, List<String>> versionsByName() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Summary s : list()) {
            out.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s.version());
        }
        return out;
    }
}

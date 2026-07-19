package id.behavio.qris.platform.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Alokasi port TCP LINTAS produk (design.md §3.4/§6.3).
 *
 * Sejak bank & QRIS punya schema sendiri, {@code bank.simulators.port} dan
 * {@code qris.simulators.port} masing-masing UNIQUE tapi tidak saling melihat — tanpa
 * registry ini, profil bank dan profil QRIS bisa sama-sama mengklaim port 9001 dan
 * baru gagal saat bind (error yang muncul jauh dari sebabnya). Keunikan ditegakkan DB
 * lewat PRIMARY KEY(port), bukan cek baca-lalu-tulis di aplikasi yang punya celah race.
 */
@Component
public class PortRegistry {

    private final JdbcClient db;

    public PortRegistry(JdbcClient db) {
        this.db = db;
    }

    /**
     * Klaim port untuk sebuah simulator.
     *
     * @throws IllegalArgumentException bila port sudah dipakai simulator lain (produk mana pun)
     */
    public void claim(String product, UUID simulatorId, int port) {
        // ON CONFLICT DO NOTHING, bukan tangkap DuplicateKeyException: unique violation
        // membuat transaksi Postgres masuk status aborted, sehingga SELECT pemilik port
        // untuk menyusun pesan error justru gagal dengan 25P02 ("current transaction is
        // aborted") dan menutupi 409 jadi 500. Cara ini menjaga transaksi tetap hidup.
        int inserted = db.sql("INSERT INTO platform.port_registry (port, product, simulator_id) "
                        + "VALUES (?, ?, ?) ON CONFLICT (port) DO NOTHING")
                .param(port).param(product).param(simulatorId)
                .update();
        if (inserted == 0) {
            throw new IllegalArgumentException("Port " + port + " sudah dipakai " + describe(port));
        }
    }

    public void release(UUID simulatorId) {
        db.sql("DELETE FROM platform.port_registry WHERE simulator_id = ?")
                .param(simulatorId).update();
    }

    /** Siapa pemakai port ini — untuk pesan error yang menyebut produknya. */
    public Optional<String> owner(int port) {
        return db.sql("SELECT product FROM platform.port_registry WHERE port = ?")
                .param(port)
                .query(String.class).optional();
    }

    private String describe(int port) {
        return owner(port).map(p -> "profil " + p).orElse("simulator lain");
    }
}

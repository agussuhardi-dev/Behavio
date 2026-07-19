package id.behavio.qris.platform.persistence;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Saat app boot, port aktual selalu tertutup (Port/Server Manager mulai kosong di
 * memori) — tapi status di DB bisa masih 'RUNNING' dari sesi sebelumnya. Paksa SEMUA
 * simulator di SEMUA produk ke STOPPED agar status DB cocok realita (design.md §6.3).
 *
 * Sekaligus membersihkan {@code platform.port_registry} dari baris yatim: baris di sana
 * sengaja tanpa FK (menunjuk bank.simulators ATAU qris.simulators tergantung kolom
 * product), jadi tak ikut terhapus lewat CASCADE bila simulatornya lenyap — port bisa
 * "tersandera" selamanya tanpa pembersihan ini.
 */
@Component
@Order(10) // sebelum seeder produk (Order 20)
public class ResetStatusOnBoot implements CommandLineRunner {

    private final JdbcClient db;
    private final List<SchemaTables> schemas;

    public ResetStatusOnBoot(JdbcClient db, List<SchemaTables> schemas) {
        this.db = db;
        this.schemas = schemas;
    }

    @Override
    public void run(String... args) {
        for (SchemaTables t : schemas) {
            db.sql("UPDATE " + t.simulators() + " SET status = 'STOPPED' WHERE status <> 'STOPPED'").update();
            db.sql("DELETE FROM platform.port_registry r WHERE r.product = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM " + t.simulators() + " s WHERE s.id = r.simulator_id)")
                    .param(t.schema())
                    .update();
        }
    }
}

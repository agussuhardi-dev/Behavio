package id.behavio.bank.persistence;

import id.behavio.bank.platform.core.port.SimulatorAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seed demo profil BANK (idempoten): 1 simulator STOPPED di port 9001 + partner
 * PARTNER001 + 2 rekening + seluruh endpoint/scenario katalog bank.
 *
 * Kini cukup memanggil {@code SimulatorAdmin.create} — satu jalur dengan tombol "Tambah
 * Profil" di dashboard. Sebelum pemisahan, seeder menyusun sendiri endpoint & scenario
 * lewat JPA, jadi daftarnya gampang menyimpang dari yang dibuat lewat Admin API.
 */
@Order(20)
public class BankDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BankDemoSeeder.class);

    private final JdbcClient db;
    private final SimulatorAdmin admin;

    public BankDemoSeeder(JdbcClient db, SimulatorAdmin admin) {
        this.db = db;
        this.admin = admin;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Long count = db.sql("SELECT count(*) FROM bank.simulators").query(Long.class).single();
        if (count != null && count > 0) {
            log.info("[seed:bank] simulator sudah ada ({}), lewati seeding", count);
            return;
        }
        UUID simId = admin.create("Bank Simulasi Demo", 9001, "SIMULATED");
        log.info("[seed:bank] siap — simulator={} port=9001 partner=PARTNER001 "
                + "source=1234567890(1.000.000) benef=9876543210(0). Scenario aktif: Normal.", simId);
    }
}

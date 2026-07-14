package id.behavio.qris.persistence;

import id.behavio.core.port.SimulatorAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seed demo profil QRIS (idempoten): 1 profil PJP STOPPED di port 9101 + partner
 * PARTNER001 + seluruh endpoint/scenario katalog QRIS.
 *
 * Port 9101 (bukan 9001) karena profil QRIS kini entitas tersendiri dengan port sendiri
 * — sebelum pemisahan, endpoint QRIS menumpang port profil bank.
 */
@Order(20)
public class QrisDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(QrisDemoSeeder.class);

    private final JdbcClient db;
    private final SimulatorAdmin admin;

    public QrisDemoSeeder(JdbcClient db, SimulatorAdmin admin) {
        this.db = db;
        this.admin = admin;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Long count = db.sql("SELECT count(*) FROM qris.simulators").query(Long.class).single();
        if (count != null && count > 0) {
            log.info("[seed:qris] profil sudah ada ({}), lewati seeding", count);
            return;
        }
        UUID simId = admin.create("PJP Simulasi Demo", 9101, "SIMULATED");
        log.info("[seed:qris] siap — simulator={} port=9101 partner=PARTNER001. Scenario aktif: Normal.", simId);
    }
}

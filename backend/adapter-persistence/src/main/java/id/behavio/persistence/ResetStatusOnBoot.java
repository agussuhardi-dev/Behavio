package id.behavio.persistence;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Saat app boot, port aktual selalu tertutup (Port/Server Manager mulai kosong di
 * memori) — tapi status di DB bisa masih 'RUNNING' dari sesi sebelumnya. Paksa semua
 * simulator ke STOPPED agar status DB selalu cocok realita (design.md §6.3).
 */
@Component
@Order(10) // sebelum DemoSeeder (Order 20)
public class ResetStatusOnBoot implements CommandLineRunner {

    private final JdbcClient db;

    public ResetStatusOnBoot(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void run(String... args) {
        db.sql("UPDATE simulators SET status = 'STOPPED' WHERE status <> 'STOPPED'").update();
    }
}

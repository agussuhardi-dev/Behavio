package id.behavio.bank.persistence;

import id.behavio.bank.platform.persistence.BaselineExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Kait provisioning khusus BANK: rekening baseline & penyalinan rekening saat clone.
 *
 * Mesin generik ({@code SchemaSimulatorAdmin}) tak tahu apa itu rekening — profil QRIS
 * memang tak punya. Sebelum pemisahan, logika ini tertanam langsung di dalam mesin
 * provisioning bersama.
 */
public class BankBaseline implements BaselineExtension {

    private final JdbcClient db;

    public BankBaseline(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void afterProvision(UUID simulatorId, UUID partnerId) {
        account(simulatorId, partnerId, "1234567890", "Andi Sumber", "1000000.00");
        account(simulatorId, partnerId, "9876543210", "Budi Tujuan", "0.00");
    }

    @Override
    public void afterClone(UUID sourceSimulatorId, UUID newSimulatorId, UUID newPartnerId) {
        // Rekening baseline diganti salinan rekening sumber — clone membawa starting state,
        // lalu berjalan independen sepenuhnya.
        db.sql("DELETE FROM bank.accounts WHERE simulator_id = ?")
                .param(newSimulatorId).update();
        db.sql("""
                INSERT INTO bank.accounts (id, simulator_id, partner_id, account_no, holder_name, currency, balance)
                SELECT gen_random_uuid(), ?, ?, account_no, holder_name, currency, balance
                FROM bank.accounts WHERE simulator_id = ?
                """)
                .param(newSimulatorId).param(newPartnerId).param(sourceSimulatorId)
                .update();
    }

    private void account(UUID simulatorId, UUID partnerId, String no, String holder, String balance) {
        db.sql("""
                INSERT INTO bank.accounts (id, simulator_id, partner_id, account_no, holder_name, currency, balance)
                VALUES (?, ?, ?, ?, ?, 'IDR', ?::numeric)
                """)
                .param(UUID.randomUUID()).param(simulatorId).param(partnerId).param(no).param(holder).param(balance)
                .update();
    }
}

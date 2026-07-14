package id.behavio.persistence;

import id.behavio.core.port.AccountAdmin;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Implementasi AccountAdmin via JdbcClient — CRUD Partner & Account dari dashboard. */
@Repository
public class AccountAdminJdbc implements AccountAdmin {

    private final JdbcClient db;

    public AccountAdminJdbc(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<PartnerView> listPartners(UUID simulatorId) {
        return db.sql("""
                SELECT id, partner_id, public_key, client_secret FROM partners
                WHERE simulator_id = ? ORDER BY partner_id
                """)
                .param(simulatorId)
                .query((rs, n) -> new PartnerView(
                        rs.getObject("id", UUID.class), rs.getString("partner_id"),
                        rs.getString("public_key") != null, rs.getString("client_secret") != null))
                .list();
    }

    @Override
    public UUID createPartner(UUID simulatorId, String partnerId, String publicKeyPem, String clientSecret) {
        UUID id = UUID.randomUUID();
        try {
            db.sql("""
                    INSERT INTO partners (id, simulator_id, partner_id, public_key, client_secret)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .param(id).param(simulatorId).param(partnerId)
                    .param(blankToNull(publicKeyPem)).param(blankToNull(clientSecret))
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Partner '" + partnerId + "' sudah ada di simulator ini");
        }
        return id;
    }

    @Override
    public void deletePartner(UUID simulatorId, UUID partnerRowId) {
        db.sql("DELETE FROM partners WHERE id = ? AND simulator_id = ?")
                .param(partnerRowId).param(simulatorId).update();
    }

    @Override
    public List<AccountView> listAccounts(UUID simulatorId) {
        return db.sql("""
                SELECT a.id, a.partner_id, p.partner_id AS partner_label, a.account_no,
                       a.holder_name, a.currency, a.balance
                FROM accounts a JOIN partners p ON a.partner_id = p.id
                WHERE a.simulator_id = ? ORDER BY p.partner_id, a.account_no
                """)
                .param(simulatorId)
                .query((rs, n) -> new AccountView(
                        rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class),
                        rs.getString("partner_label"), rs.getString("account_no"),
                        rs.getString("holder_name"), rs.getString("currency"), rs.getBigDecimal("balance")))
                .list();
    }

    @Override
    public UUID createAccount(UUID simulatorId, UUID partnerRowId, String accountNo, String holderName, BigDecimal balance) {
        UUID id = UUID.randomUUID();
        try {
            db.sql("""
                    INSERT INTO accounts (id, simulator_id, partner_id, account_no, holder_name, currency, balance)
                    VALUES (?, ?, ?, ?, ?, 'IDR', ?)
                    """)
                    .param(id).param(simulatorId).param(partnerRowId).param(accountNo)
                    .param(holderName).param(balance == null ? BigDecimal.ZERO : balance)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Nomor rekening '" + accountNo + "' sudah ada untuk partner ini");
        }
        return id;
    }

    @Override
    public void setBalance(UUID simulatorId, UUID accountId, BigDecimal newBalance) {
        if (newBalance == null || newBalance.signum() < 0) {
            throw new IllegalArgumentException("Saldo tidak boleh negatif");
        }
        int updated = db.sql("UPDATE accounts SET balance = ? WHERE id = ? AND simulator_id = ?")
                .param(newBalance).param(accountId).param(simulatorId)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Rekening tidak ditemukan");
        }
    }

    @Override
    public void deleteAccount(UUID simulatorId, UUID accountId) {
        db.sql("DELETE FROM accounts WHERE id = ? AND simulator_id = ?")
                .param(accountId).param(simulatorId).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

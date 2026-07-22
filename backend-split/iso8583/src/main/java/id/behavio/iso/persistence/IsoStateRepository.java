package id.behavio.iso.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * State produk ISO-8583: rekening, kartu, jejak transaksi.
 *
 * <p>Terpisah penuh dari {@code bank.accounts} — saldo di sini bukan saldo bank simulator
 * ({@code docs/iso8583-plan.md} §3 poin 4).
 */
@Repository
public class IsoStateRepository {

    private final JdbcClient db;

    public IsoStateRepository(JdbcClient db) {
        this.db = db;
    }

    public record Account(UUID id, String accountNo, String holderName,
                          BigDecimal balance, String currency) {}

    public Optional<Account> findAccount(UUID simulatorId, String accountNo) {
        return db.sql("""
                SELECT id, account_no, holder_name, balance, currency
                FROM iso8583.accounts WHERE simulator_id = ? AND account_no = ?
                """)
                .param(simulatorId).param(accountNo)
                .query(Account.class).optional();
    }

    /** Lewat kartu: PAN → rekening. Transaksi POS umumnya hanya membawa PAN. */
    public Optional<Account> findAccountByPan(UUID simulatorId, String pan) {
        return db.sql("""
                SELECT a.id, a.account_no, a.holder_name, a.balance, a.currency
                FROM iso8583.cards c
                JOIN iso8583.accounts a
                  ON a.simulator_id = c.simulator_id AND a.account_no = c.account_no
                WHERE c.simulator_id = ? AND c.pan = ? AND c.status = 'ACTIVE'
                """)
                .param(simulatorId).param(pan)
                .query(Account.class).optional();
    }

    public List<Account> listAccounts(UUID simulatorId) {
        return db.sql("""
                SELECT id, account_no, holder_name, balance, currency
                FROM iso8583.accounts WHERE simulator_id = ? ORDER BY account_no
                """)
                .param(simulatorId).query(Account.class).list();
    }

    /**
     * Debit dengan syarat saldo di SQL-nya sendiri, bukan cek-lalu-tulis di aplikasi:
     * dua transaksi bersamaan pada rekening yang sama bisa lolos pemeriksaan aplikasi
     * dan membuat saldo minus.
     */
    @Transactional
    public boolean debit(UUID simulatorId, String accountNo, BigDecimal amount) {
        return db.sql("""
                UPDATE iso8583.accounts SET balance = balance - ?
                WHERE simulator_id = ? AND account_no = ? AND balance >= ?
                """)
                .param(amount).param(simulatorId).param(accountNo).param(amount)
                .update() > 0;
    }

    @Transactional
    public void credit(UUID simulatorId, String accountNo, BigDecimal amount) {
        db.sql("UPDATE iso8583.accounts SET balance = balance + ? WHERE simulator_id = ? AND account_no = ?")
                .param(amount).param(simulatorId).param(accountNo)
                .update();
    }

    @Transactional
    public UUID addAccount(UUID simulatorId, String accountNo, String holderName,
                           BigDecimal balance, String currency) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO iso8583.accounts (id, simulator_id, account_no, holder_name, balance, currency)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(simulatorId).param(accountNo).param(holderName)
                .param(balance).param(currency)
                .update();
        return id;
    }

    @Transactional
    public UUID addCard(UUID simulatorId, String pan, String accountNo) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO iso8583.cards (id, simulator_id, pan, account_no) VALUES (?, ?, ?, ?)")
                .param(id).param(simulatorId).param(pan).param(accountNo)
                .update();
        return id;
    }

    // ── transaksi & reversal ───────────────────────────────────────────────

    public record Txn(UUID id, String mti, String processingCode, String stan,
                      String accountNo, String counterpartNo, BigDecimal amount, boolean reversed) {}

    /** Catat transaksi finansial yang BERHASIL — hanya yang tercatat yang bisa dibalik. */
    @Transactional
    public void recordTransaction(UUID simulatorId, String mti, String processingCode, String stan,
                                  String rrn, String pan, String accountNo, String counterpartNo,
                                  BigDecimal amount, String responseCode) {
        db.sql("""
                INSERT INTO iso8583.transactions
                    (id, simulator_id, mti, processing_code, stan, rrn, pan,
                     account_no, counterpart_no, amount, response_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID()).param(simulatorId).param(mti).param(processingCode)
                .param(stan).param(rrn).param(pan).param(accountNo).param(counterpartNo)
                .param(amount).param(responseCode)
                .update();
    }

    /**
     * Cari transaksi asli untuk reversal. Dicocokkan dengan STAN + hanya yang SUKSES:
     * transaksi yang ditolak tak pernah memindahkan dana, jadi membalikkannya justru
     * menciptakan uang.
     */
    public Optional<Txn> findReversibleByStan(UUID simulatorId, String stan) {
        return db.sql("""
                SELECT id, mti, processing_code, stan, account_no, counterpart_no, amount, reversed
                FROM iso8583.transactions
                WHERE simulator_id = ? AND stan = ? AND response_code = '00'
                ORDER BY created_at DESC LIMIT 1
                """)
                .param(simulatorId).param(stan)
                .query(Txn.class).optional();
    }

    /**
     * Tandai sudah dibalik. Mengembalikan {@code false} bila SUDAH ditandai sebelumnya —
     * penjagaan idempotensi ada di SQL (bukan cek-lalu-tulis di aplikasi) supaya dua
     * reversal yang tiba bersamaan tak sama-sama lolos dan mengembalikan dana dua kali.
     */
    @Transactional
    public boolean markReversed(UUID txnId) {
        return db.sql("UPDATE iso8583.transactions SET reversed = TRUE, reversed_at = now() "
                        + "WHERE id = ? AND reversed = FALSE")
                .param(txnId).update() > 0;
    }

    @Transactional
    public void logExchange(UUID simulatorId, String mti, String operation, String responseCode,
                            String requestHex, String responseHex, long durationMillis) {
        db.sql("""
                INSERT INTO iso8583.request_logs
                    (id, simulator_id, mti, operation, response_code, request_hex, response_hex, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID()).param(simulatorId).param(mti).param(operation)
                .param(responseCode).param(requestHex).param(responseHex).param(durationMillis)
                .update();
    }

    public List<java.util.Map<String, Object>> recentLogs(UUID simulatorId, int limit) {
        return db.sql("""
                SELECT mti, operation, response_code, request_hex, response_hex, duration_ms, created_at
                FROM iso8583.request_logs WHERE simulator_id = ?
                ORDER BY created_at DESC LIMIT ?
                """)
                .param(simulatorId).param(Math.min(200, limit))
                .query().listOfRows();
    }
}

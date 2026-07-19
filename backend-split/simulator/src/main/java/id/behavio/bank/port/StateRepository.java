package id.behavio.bank.port;

import id.behavio.bank.domain.Account;
import id.behavio.bank.platform.core.port.StoredResponse;
import id.behavio.bank.domain.Transaction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: akses & mutasi state tersimulasi.
 * Implementasi (adapter-persistence) menjamin atomicity mutasi uang — adapter web
 * membungkus seluruh pipeline dalam satu DB transaction (design.md §4.1).
 * Semua operasi terikat (simulatorId, partnerId) — isolasi per-partner.
 */
public interface StateRepository {

    Optional<Account> findAccount(UUID simulatorId, UUID partnerId, String accountNo);

    void saveAccount(Account account);

    void saveTransaction(Transaction transaction);

    List<Transaction> findTransactions(UUID simulatorId, UUID partnerId, Instant from, Instant to, int limit, int offset);

    /** Cek idempotensi SNAP (X-EXTERNAL-ID). */
    boolean externalIdExists(UUID simulatorId, UUID partnerId, String externalId);

    void recordExternalId(UUID simulatorId, UUID partnerId, String externalId, StoredResponse response);

    Optional<StoredResponse> findStoredResponse(UUID simulatorId, UUID partnerId, String externalId);
}

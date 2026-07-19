package id.behavio.bank.persistence;

import id.behavio.bank.domain.Account;
import id.behavio.bank.domain.Transaction;
import id.behavio.bank.domain.TransactionStatus;
import id.behavio.bank.port.StateRepository;
import id.behavio.bank.platform.core.port.StoredResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound adapter: {@link StateRepository} berbasis JPA/Postgres (schema "bank").
 * Memakai EntityManager langsung. Atomicity mutasi uang dijamin transaksi yang dibuka
 * adapter web (pipeline dieksekusi dalam satu DB transaction — design.md §4.1).
 *
 * Satu-satunya tempat JPA masih dipakai: di sini ia membayar dirinya sendiri (state uang
 * yang harus atomik) dan tak perlu diduplikasi per-schema karena rekening & transaksi
 * hanya ada di produk bank.
 * Tanpa @Repository: bean ini dirakit eksplisit di *ProductConfig (kalau di-scan juga,
 * Spring melihat dua bean bertipe sama). Efek samping yang justru diinginkan — @Repository
 * memasang exception-translation AOP yang mengubah IllegalArgumentException jadi
 * InvalidDataAccessApiUsageException sebelum sampai ke controller, biang 500 mentah
 * menggantikan 400/409 yang rapi.
 */
public class JpaStateRepository implements StateRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Account> findAccount(UUID simulatorId, UUID partnerId, String accountNo) {
        List<AccountEntity> rows = em.createQuery(
                        "select a from AccountEntity a where a.simulatorId = :sim and a.partnerId = :p and a.accountNo = :no",
                        AccountEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("p", partnerId)
                .setParameter("no", accountNo)
                .getResultList();
        return rows.stream().findFirst().map(e ->
                new Account(e.id, e.simulatorId, e.partnerId, e.accountNo, e.holderName, e.currency, e.balance));
    }

    @Override
    public void saveAccount(Account account) {
        AccountEntity e = em.find(AccountEntity.class, account.id());
        if (e == null) {
            e = new AccountEntity();
            e.id = account.id();
            e.simulatorId = account.simulatorId();
            e.partnerId = account.partnerId();
            e.accountNo = account.accountNo();
        }
        e.holderName = account.holderName();
        e.currency = account.currency();
        e.balance = account.balance();
        em.merge(e);
    }

    @Override
    public void saveTransaction(Transaction t) {
        TransactionEntity e = new TransactionEntity();
        e.id = t.id();
        e.simulatorId = t.simulatorId();
        e.partnerId = t.partnerId();
        e.referenceNo = t.referenceNo();
        e.partnerReferenceNo = t.partnerReferenceNo();
        e.sourceAccountNo = t.sourceAccountNo();
        e.beneficiaryAccountNo = t.beneficiaryAccountNo();
        e.amount = t.amount();
        e.currency = t.currency();
        e.status = t.status().name();
        e.createdAt = t.createdAt();
        em.persist(e);
    }

    @Override
    public List<Transaction> findTransactions(UUID simulatorId, UUID partnerId,
                                               Instant from, Instant to, int limit, int offset) {
        return em.createQuery(
                        "select e from TransactionEntity e "
                        + "where e.simulatorId = :sim and e.partnerId = :p "
                        + "and e.createdAt >= :from and e.createdAt <= :to "
                        + "order by e.createdAt desc",
                        TransactionEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("p", partnerId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(limit)
                .setFirstResult(offset)
                .getResultList().stream()
                .map(e -> new Transaction(e.id, e.simulatorId, e.partnerId,
                        e.referenceNo, e.partnerReferenceNo,
                        e.sourceAccountNo, e.beneficiaryAccountNo,
                        e.amount, e.currency,
                        TransactionStatus.valueOf(e.status),
                        e.createdAt))
                .toList();
    }

    @Override
    public boolean externalIdExists(UUID simulatorId, UUID partnerId, String externalId) {
        return find(simulatorId, partnerId, externalId).isPresent();
    }

    @Override
    public void recordExternalId(UUID simulatorId, UUID partnerId, String externalId, StoredResponse response) {
        IdempotencyEntity e = new IdempotencyEntity();
        e.id = UUID.randomUUID();
        e.simulatorId = simulatorId;
        e.partnerId = partnerId;
        e.externalId = externalId;
        e.storedResponse = encode(response);
        em.persist(e);
    }

    @Override
    public Optional<StoredResponse> findStoredResponse(UUID simulatorId, UUID partnerId, String externalId) {
        return find(simulatorId, partnerId, externalId).map(e -> decode(e.storedResponse));
    }

    private Optional<IdempotencyEntity> find(UUID simulatorId, UUID partnerId, String externalId) {
        return em.createQuery(
                        "select i from IdempotencyEntity i where i.simulatorId = :sim and i.partnerId = :p and i.externalId = :x",
                        IdempotencyEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("p", partnerId)
                .setParameter("x", externalId)
                .getResultList().stream().findFirst();
    }

    // stored_response TEXT = "httpStatus|responseCode|body" (split limit 3 → body utuh walau ada '|')
    private static String encode(StoredResponse r) {
        return r.httpStatus() + "|" + r.responseCode() + "|" + r.body();
    }

    private static StoredResponse decode(String s) {
        String[] parts = s.split("\\|", 3);
        int status = Integer.parseInt(parts[0]);
        String code = parts.length > 1 ? parts[1] : "";
        String body = parts.length > 2 ? parts[2] : "";
        return new StoredResponse(status, code, body);
    }
}

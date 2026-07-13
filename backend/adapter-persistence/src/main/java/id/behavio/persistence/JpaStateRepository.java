package id.behavio.persistence;

import id.behavio.core.domain.Account;
import id.behavio.core.domain.Transaction;
import id.behavio.core.port.StateRepository;
import id.behavio.core.port.StoredResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound adapter: {@link StateRepository} berbasis JPA/Postgres. Memakai EntityManager
 * langsung. Atomicity mutasi uang dijamin transaksi yang dibuka adapter web (pipeline
 * dieksekusi dalam satu DB transaction — design.md §4.1).
 */
@Repository
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
        em.persist(e);
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

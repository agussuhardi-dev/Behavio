package id.behavio.bank.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity JPA produk BANK (pemetaan tabel Liquibase di schema "bank"). Sengaja
 * package-private & terkumpul — hanya dipakai internal :product-bank; core memakai
 * domain murni, bukan entity.
 *
 * Hanya berisi state UANG (accounts, transactions, idempotency), tempat JPA memang
 * membayar dirinya sendiri: mutasi saldo harus atomik & terjaga integritasnya.
 * Tabel konfigurasi (simulators/partners/endpoints/scenarios) TIDAK dipetakan di sini —
 * strukturnya identik dengan schema qris dan dikelola mesin generik ber-parameter schema
 * di :adapter-persistence lewat JdbcClient, ditulis sekali untuk kedua produk.
 */
final class BankEntities {
    private BankEntities() {}
}

@Entity
@Table(name = "accounts", schema = "bank")
class AccountEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    @Column(name = "partner_id") UUID partnerId;
    @Column(name = "account_no") String accountNo;
    @Column(name = "holder_name") String holderName;
    String currency;
    BigDecimal balance;

    AccountEntity() {}
}

@Entity
@Table(name = "transactions", schema = "bank")
class TransactionEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    @Column(name = "partner_id") UUID partnerId;
    @Column(name = "reference_no") String referenceNo;
    @Column(name = "partner_reference_no") String partnerReferenceNo;
    @Column(name = "source_account_no") String sourceAccountNo;
    @Column(name = "beneficiary_account_no") String beneficiaryAccountNo;
    BigDecimal amount;
    String currency;
    String status;
    @Column(name = "created_at") Instant createdAt;

    TransactionEntity() {}
}

@Entity
@Table(name = "idempotency", schema = "bank")
class IdempotencyEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    @Column(name = "partner_id") UUID partnerId;
    @Column(name = "external_id") String externalId;
    @Column(name = "stored_response") String storedResponse;

    IdempotencyEntity() {}
}

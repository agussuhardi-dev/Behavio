package id.behavio.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entity JPA (pemetaan tabel Liquibase). Sengaja package-private & terkumpul —
 * hanya dipakai internal adapter-persistence; core memakai domain murni, bukan entity.
 */
final class Entities {
    private Entities() {}
}

@Entity
@Table(name = "simulators")
class SimulatorEntity {
    @Id
    UUID id;
    String name;
    Integer port;
    String status;
    @Column(name = "signature_mode") String signatureMode;

    SimulatorEntity() {}
}

@Entity
@Table(name = "accounts")
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
@Table(name = "transactions")
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

    TransactionEntity() {}
}

@Entity
@Table(name = "idempotency")
class IdempotencyEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    @Column(name = "partner_id") UUID partnerId;
    @Column(name = "external_id") String externalId;
    @Column(name = "stored_response") String storedResponse;

    IdempotencyEntity() {}
}

@Entity
@Table(name = "partners")
class PartnerEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    @Column(name = "partner_id") String partnerId;
    @Column(name = "public_key") String publicKey;
    @Column(name = "client_secret") String clientSecret;

    PartnerEntity() {}
}

@Entity
@Table(name = "endpoints")
class EndpointEntity {
    @Id
    UUID id;
    @Column(name = "simulator_id") UUID simulatorId;
    String method;
    String path;
    @Column(name = "active_scenario_id") UUID activeScenarioId;
    /** Kunci operasi stabil (mis. "transfer","qris-generate") — path boleh di-custom, ini tidak. */
    String operation;

    // Kolom jsonb (endpoints.headers) sengaja TIDAK dipetakan di sini — Hibernate akan
    // mengikatnya sebagai varchar dan setiap update entity gagal (jsonb <> varchar).
    // Dikelola EndpointRegistryJdbc yang meng-cast eksplisit (?::jsonb / headers::text),
    // sama seperti scenarios.definition pada ScenarioEntity.

    EndpointEntity() {}
}

@Entity
@Table(name = "scenarios")
class ScenarioEntity {
    @Id
    UUID id;
    @Column(name = "endpoint_id") UUID endpointId;
    String name;

    ScenarioEntity() {}
}

package id.behavio.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transaksi tersimulasi (mis. transfer intrabank). Pembawa uang.
 * Terikat (simulatorId, partnerId).
 */
public class Transaction {

    private final UUID id;
    private final UUID simulatorId;
    private final UUID partnerId;
    private final String referenceNo;        // dikeluarkan simulator
    private final String partnerReferenceNo; // dari partner
    private final String sourceAccountNo;
    private final String beneficiaryAccountNo;
    private final BigDecimal amount;
    private final String currency;
    private TransactionStatus status;
    private final Instant createdAt;

    public Transaction(UUID id, UUID simulatorId, UUID partnerId,
                       String referenceNo, String partnerReferenceNo,
                       String sourceAccountNo, String beneficiaryAccountNo,
                       BigDecimal amount, String currency,
                       TransactionStatus status, Instant createdAt) {
        this.id = id;
        this.simulatorId = simulatorId;
        this.partnerId = partnerId;
        this.referenceNo = referenceNo;
        this.partnerReferenceNo = partnerReferenceNo;
        this.sourceAccountNo = sourceAccountNo;
        this.beneficiaryAccountNo = beneficiaryAccountNo;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void markSuccess() { this.status = TransactionStatus.SUCCESS; }
    public void markFailed() { this.status = TransactionStatus.FAILED; }

    public UUID id() { return id; }
    public UUID simulatorId() { return simulatorId; }
    public UUID partnerId() { return partnerId; }
    public String referenceNo() { return referenceNo; }
    public String partnerReferenceNo() { return partnerReferenceNo; }
    public String sourceAccountNo() { return sourceAccountNo; }
    public String beneficiaryAccountNo() { return beneficiaryAccountNo; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public TransactionStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}

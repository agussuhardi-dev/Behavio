package id.behavio.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * QR (MPM) tersimulasi (design.md Lampiran A3). Entitas non-uang (disimpan di tabel
 * generik {@code entities}), terikat (simulatorId, partnerId) — isolasi per-partner.
 */
public class QrisTransaction {

    private final UUID id;
    private final UUID simulatorId;
    private final UUID partnerId;
    private final String partnerReferenceNo;
    private final String referenceNo;
    private final String merchantId;
    private final String terminalId;
    private final QrisType qrType;
    /** Nominal tertanam (dynamic) — null untuk static sebelum dibayar. */
    private final BigDecimal amount;
    private final String currency;
    private final String qrContent;
    private final String callbackUrl;
    private QrisStatus status;
    /** Nominal aktual yang dibayar (diisi saat markPaid — wajib untuk static). */
    private BigDecimal paidAmount;
    private BigDecimal refundedAmount;
    private final Instant createdAt;

    public QrisTransaction(UUID id, UUID simulatorId, UUID partnerId, String partnerReferenceNo,
                           String referenceNo, String merchantId, String terminalId, QrisType qrType,
                           BigDecimal amount, String currency, String qrContent, String callbackUrl,
                           QrisStatus status, BigDecimal paidAmount, BigDecimal refundedAmount,
                           Instant createdAt) {
        this.id = id;
        this.simulatorId = simulatorId;
        this.partnerId = partnerId;
        this.partnerReferenceNo = partnerReferenceNo;
        this.referenceNo = referenceNo;
        this.merchantId = merchantId;
        this.terminalId = terminalId;
        this.qrType = qrType;
        this.amount = amount;
        this.currency = currency;
        this.qrContent = qrContent;
        this.callbackUrl = callbackUrl;
        this.status = status;
        this.paidAmount = paidAmount;
        this.refundedAmount = refundedAmount;
        this.createdAt = createdAt;
    }

    public void markPaid(BigDecimal paidAmount) {
        this.status = QrisStatus.PAID;
        this.paidAmount = paidAmount;
    }

    public void markRefunded(BigDecimal refundedAmount) {
        this.status = QrisStatus.REFUNDED;
        this.refundedAmount = refundedAmount;
    }

    public UUID id() { return id; }
    public UUID simulatorId() { return simulatorId; }
    public UUID partnerId() { return partnerId; }
    public String partnerReferenceNo() { return partnerReferenceNo; }
    public String referenceNo() { return referenceNo; }
    public String merchantId() { return merchantId; }
    public String terminalId() { return terminalId; }
    public QrisType qrType() { return qrType; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public String qrContent() { return qrContent; }
    public String callbackUrl() { return callbackUrl; }
    public QrisStatus status() { return status; }
    public BigDecimal paidAmount() { return paidAmount; }
    public BigDecimal refundedAmount() { return refundedAmount; }
    public Instant createdAt() { return createdAt; }
}

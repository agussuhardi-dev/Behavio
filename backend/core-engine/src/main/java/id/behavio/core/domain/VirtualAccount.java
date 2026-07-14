package id.behavio.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Virtual Account tersimulasi (design.md Lampiran A2 — SNAP BI create-va).
 * Entitas non-uang (disimpan di tabel generik {@code entities}), terikat
 * (simulatorId, partnerId) — isolasi penuh per-partner.
 */
public class VirtualAccount {

    private final UUID id;
    private final UUID simulatorId;
    private final UUID partnerId;
    private final String partnerServiceId;
    private final String customerNo;
    private final String virtualAccountNo;
    private final String virtualAccountName;
    private final String virtualAccountEmail;
    private final String virtualAccountPhone;
    private final BigDecimal totalAmount;
    private final String currency;
    private final String virtualAccountTrxType; // C|O|V
    private final String expiredDate;
    private final String trxId;
    /** URL callback (X-CALLBACK-URL saat create) — dipakai Payment Notification. */
    private final String callbackUrl;
    private VirtualAccountStatus status;
    private final Instant createdAt;

    public VirtualAccount(UUID id, UUID simulatorId, UUID partnerId, String partnerServiceId,
                          String customerNo, String virtualAccountNo, String virtualAccountName,
                          String virtualAccountEmail, String virtualAccountPhone,
                          BigDecimal totalAmount, String currency, String virtualAccountTrxType,
                          String expiredDate, String trxId, String callbackUrl,
                          VirtualAccountStatus status, Instant createdAt) {
        this.id = id;
        this.simulatorId = simulatorId;
        this.partnerId = partnerId;
        this.partnerServiceId = partnerServiceId;
        this.customerNo = customerNo;
        this.virtualAccountNo = virtualAccountNo;
        this.virtualAccountName = virtualAccountName;
        this.virtualAccountEmail = virtualAccountEmail;
        this.virtualAccountPhone = virtualAccountPhone;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.virtualAccountTrxType = virtualAccountTrxType;
        this.expiredDate = expiredDate;
        this.trxId = trxId;
        this.callbackUrl = callbackUrl;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void markPaid() { this.status = VirtualAccountStatus.PAID; }

    public UUID id() { return id; }
    public UUID simulatorId() { return simulatorId; }
    public UUID partnerId() { return partnerId; }
    public String partnerServiceId() { return partnerServiceId; }
    public String customerNo() { return customerNo; }
    public String virtualAccountNo() { return virtualAccountNo; }
    public String virtualAccountName() { return virtualAccountName; }
    public String virtualAccountEmail() { return virtualAccountEmail; }
    public String virtualAccountPhone() { return virtualAccountPhone; }
    public BigDecimal totalAmount() { return totalAmount; }
    public String currency() { return currency; }
    public String virtualAccountTrxType() { return virtualAccountTrxType; }
    public String expiredDate() { return expiredDate; }
    public String trxId() { return trxId; }
    public String callbackUrl() { return callbackUrl; }
    public VirtualAccountStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}

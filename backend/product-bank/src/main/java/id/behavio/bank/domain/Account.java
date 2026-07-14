package id.behavio.bank.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rekening tersimulasi. Pembawa uang → integritas saldo dijaga (saldo ≥ 0).
 * Terikat (simulatorId, partnerId) — isolasi penuh per-partner.
 */
public class Account {

    private final UUID id;
    private final UUID simulatorId;
    private final UUID partnerId;
    private final String accountNo;
    private String holderName;
    private String currency;
    private BigDecimal balance;

    public Account(UUID id, UUID simulatorId, UUID partnerId, String accountNo,
                   String holderName, String currency, BigDecimal balance) {
        this.id = id;
        this.simulatorId = simulatorId;
        this.partnerId = partnerId;
        this.accountNo = accountNo;
        this.holderName = holderName;
        this.currency = currency;
        this.balance = balance;
    }

    /** Debit dengan penjagaan saldo (tidak boleh minus). */
    public void debit(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount harus > 0");
        }
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountNo);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount harus > 0");
        }
        this.balance = this.balance.add(amount);
    }

    public UUID id() { return id; }
    public UUID simulatorId() { return simulatorId; }
    public UUID partnerId() { return partnerId; }
    public String accountNo() { return accountNo; }
    public String holderName() { return holderName; }
    public String currency() { return currency; }
    public BigDecimal balance() { return balance; }
}

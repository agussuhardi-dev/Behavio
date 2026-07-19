package id.behavio.bank.domain;

/** Dilempar saat debit melebihi saldo. Dipetakan ke responseCode 4001714. */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountNo) {
        super("Insufficient funds on account " + accountNo);
    }
}

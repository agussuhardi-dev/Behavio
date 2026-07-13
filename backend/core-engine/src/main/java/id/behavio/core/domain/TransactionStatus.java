package id.behavio.core.domain;

/** Status transaksi (state machine sederhana untuk alur transfer). */
public enum TransactionStatus {
    PENDING,
    SUCCESS,
    FAILED
}

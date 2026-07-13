package id.behavio.core.rule;

import id.behavio.core.domain.TransactionStatus;

/**
 * Aksi mutasi state (bagian THEN). Dieksekusi berurutan di dalam satu unit atomik
 * (design.md §4.1) — adapter web membungkus pipeline dalam satu DB transaction.
 */
public sealed interface Action
        permits Action.Debit, Action.Credit, Action.CreateTransaction {

    /** Debit saldo account (nomor dari field). Jumlah dari field. Menjaga saldo ≥ 0. */
    record Debit(String accountNoField, String amountField) implements Action {}

    /** Credit saldo account bila account ditemukan (intrabank: rekening tujuan internal). */
    record Credit(String accountNoField, String amountField) implements Action {}

    /** Catat transaksi dengan status awal tertentu. */
    record CreateTransaction(TransactionStatus status) implements Action {}
}

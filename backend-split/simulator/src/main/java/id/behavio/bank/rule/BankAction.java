package id.behavio.bank.rule;

import id.behavio.bank.domain.TransactionStatus;
import id.behavio.bank.platform.core.rule.Action;

/**
 * Aksi mutasi state milik produk BANK. Sebelum pemisahan bank/QRIS, record ini tinggal
 * di {@code id.behavio.bank.platform.core.rule.Action} dan membuat mesin generik bergantung pada
 * {@code TransactionStatus} — tipe yang hanya berarti untuk bank.
 */
public sealed interface BankAction extends Action
        permits BankAction.Debit, BankAction.Credit, BankAction.CreateTransaction {

    /** Debit saldo account (nomor dari field). Jumlah dari field. Menjaga saldo >= 0. */
    record Debit(String accountNoField, String amountField) implements BankAction {}

    /** Credit saldo account bila account ditemukan (intrabank: rekening tujuan internal). */
    record Credit(String accountNoField, String amountField) implements BankAction {}

    /** Catat transaksi dengan status awal tertentu. */
    record CreateTransaction(TransactionStatus status) implements BankAction {}
}

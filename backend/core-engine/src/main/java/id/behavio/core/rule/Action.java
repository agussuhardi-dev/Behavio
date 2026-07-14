package id.behavio.core.rule;

/**
 * Aksi mutasi state (bagian THEN) — sengaja hanya PENANDA, tanpa anggota.
 *
 * Kosakata aksi milik PRODUK, bukan mesin: {@code debit/credit/createTransaction} hanya
 * berarti bagi :product-bank (lihat {@code id.behavio.bank.rule.BankAction}); produk QRIS
 * tak memutasi saldo sama sekali sehingga daftar aksinya selalu kosong. Sebelum
 * pemisahan, record aksi bank tertanam di sini dan menyeret {@code TransactionStatus}
 * (tipe milik bank) ikut masuk ke core.
 *
 * Mesin generik hanya membawa {@code List<Action>} tanpa menafsirkannya: yang
 * mengeksekusi = engine produk, yang menyimpan/memuat ke JSON = {@code ActionCodec}
 * produk (lihat {@code id.behavio.core.product.ActionCodec}). Dieksekusi berurutan di
 * dalam satu unit atomik (design.md §4.1).
 */
public interface Action {
}

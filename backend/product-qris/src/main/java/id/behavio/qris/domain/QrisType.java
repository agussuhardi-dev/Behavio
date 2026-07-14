package id.behavio.qris.domain;

/**
 * Dynamic: QR dibuat per-transaksi, nominal sudah tertanam (sekali pakai).
 * Static: satu QR dipajang tetap, nominal diisi pelanggan saat bayar.
 * (design.md Lampiran A3.3)
 */
public enum QrisType {
    STATIC,
    DYNAMIC
}

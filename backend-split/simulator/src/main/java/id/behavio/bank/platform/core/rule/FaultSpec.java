package id.behavio.bank.platform.core.rule;

/**
 * Spesifikasi fault injection (design.md §4.2). Titik menentukan apakah aksi state
 * dijalankan lebih dulu:
 *  - BEFORE_ACTIONS = tolak di depan (saldo utuh) — mis. Bank Down 503.
 *  - AFTER_ACTIONS  = proses dulu lalu bermasalah — mis. commit-then-drop (saldo
 *                     berubah tapi respons hilang → uji idempotensi/rekonsiliasi).
 * Efek fisik (delay/drop/corrupt) diterapkan adapter web setelah commit.
 */
public record FaultSpec(
        Point point,
        long delayMillis,
        boolean drop,
        boolean corrupt
) {
    public enum Point { BEFORE_ACTIONS, AFTER_ACTIONS }

    /** Proses aksi dulu, lalu tunda respons N ms (bank lambat / timeout). */
    public static FaultSpec delayAfter(long millis) {
        return new FaultSpec(Point.AFTER_ACTIONS, millis, false, false);
    }

    /** Proses aksi (saldo berubah) lalu DROP koneksi — respons tak terkirim. */
    public static FaultSpec commitThenDrop() {
        return new FaultSpec(Point.AFTER_ACTIONS, 0, true, false);
    }

    /** Proses aksi lalu kirim body rusak (malformed JSON). */
    public static FaultSpec corruptAfter() {
        return new FaultSpec(Point.AFTER_ACTIONS, 0, false, true);
    }

    public boolean hasPhysicalEffect() {
        return delayMillis > 0 || drop || corrupt;
    }
}

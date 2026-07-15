package id.behavio.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: registrasi URL notifikasi per partner (design.md §9.1).
 *
 * Sejak {@code X-CALLBACK-URL} dibuang, ini **satu-satunya** sumber URL tujuan webhook —
 * meniru ASPI, di mana merchant mendaftarkan URL notifikasinya ke bank/PJP di luar
 * request, bukan menitipkannya di header tiap kali.
 *
 * <p><b>Catatan sejarah yang penting.</b> Tabel di baliknya pernah dihapus (2026-07-14)
 * justru karena tak ada port seperti ini: ia bisa didaftarkan tapi tak pernah dibaca
 * saat mengirim. Bila suatu saat {@link #resolveUrl} tak lagi dipanggil jalur pengiriman,
 * seluruh registrasi kembali jadi hiasan — dan saat itu ia harus dihapus lagi, bukan
 * dibiarkan.
 */
public interface WebhookSubscriptions {

    /**
     * URL tujuan untuk sebuah event, dengan fallback ke registrasi {@code ALL} (§9.1).
     * Hanya registrasi berstatus {@code ACTIVE} yang dipertimbangkan.
     *
     * @return kosong bila partner tak punya registrasi yang cocok → webhook dilewati
     */
    Optional<String> resolveUrl(UUID simulatorId, UUID partnerId, String event);

    List<Subscription> list(UUID simulatorId);

    /** Daftarkan/perbarui URL untuk {@code (partner, event)} — idempoten per pasangan itu. */
    Subscription register(UUID simulatorId, String partnerId, String event, String url);

    void delete(UUID simulatorId, UUID subscriptionId);

    void setStatus(UUID simulatorId, UUID subscriptionId, String status);

    /**
     * @param partnerId nilai X-PARTNER-ID (bukan UUID baris) — itu yang dikenali user
     * @param status ACTIVE | INACTIVE
     */
    record Subscription(UUID id, String partnerId, String event, String url, String status) {}
}

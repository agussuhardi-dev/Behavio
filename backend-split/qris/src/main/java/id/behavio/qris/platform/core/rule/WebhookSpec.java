package id.behavio.qris.platform.core.rule;

import java.util.Map;

/**
 * Spesifikasi webhook/callback async (design.md §9).
 *
 * URL tujuan TIDAK ada di sini: ia di-resolve saat kirim dari registrasi partner lewat
 * {@link id.behavio.qris.platform.core.port.WebhookSubscriptions}, memakai {@code event} sebagai kunci
 * (§9.1). Sebelumnya field ini bernama {@code urlHeader} dan URL diambil dari header
 * {@code X-CALLBACK-URL} — header buatan Behavio yang tak ada di spec SNAP mana pun,
 * sehingga simulator memaksa klien melakukan hal yang tak pernah mereka lakukan ke bank
 * sungguhan.
 *
 * @param event kunci event untuk mencari registrasi (mis. {@code transfer-notify},
 *              {@code va-payment}, {@code qris-payment}); {@code ALL} = cocokkan
 *              registrasi umum partner.
 */
public record WebhookSpec(
        String event,
        long delayMillis,
        Map<String, Object> bodyTemplate
) {

    /** Event umum — dipakai bila sebuah webhook tak menyatakan jenisnya. */
    public static final String EVENT_ALL = "ALL";

    public WebhookSpec {
        event = event == null || event.isBlank() ? EVENT_ALL : event.trim();
    }
}

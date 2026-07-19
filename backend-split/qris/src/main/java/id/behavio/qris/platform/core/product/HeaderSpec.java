package id.behavio.qris.platform.core.product;

import java.util.List;

/**
 * Satu header yang diharapkan sebuah operasi — dipakai export OpenAPI agar request di
 * Postman sudah membawa header SNAP-nya (design.md §15).
 *
 * Header sengaja dideklarasikan produk lewat {@link ProductCatalog#requestHeaders},
 * BUKAN di-hardcode mesin: set header antar operasi berbeda nyata (access-token memakai
 * {@code X-CLIENT-KEY} + RSA, operasi transaksional memakai {@code Authorization} Bearer
 * + HMAC), dan mencabangkan mesin dengan {@code if ("access-token")} persis pola yang
 * dilarang design.md §3.4.
 *
 * @param example nilai contoh; boleh kosong bila tak ada nilai yang masuk akal
 */
public record HeaderSpec(String name, boolean required, String example, String description) {

    public static HeaderSpec required(String name, String example, String description) {
        return new HeaderSpec(name, true, example, description);
    }

    public static HeaderSpec optional(String name, String example, String description) {
        return new HeaderSpec(name, false, example, description);
    }

    /**
     * Header operasi transaksional SNAP (Lampiran A.2) — dipakai hampir semua operasi.
     * {@code X-SIGNATURE}/{@code Authorization} hanya benar-benar diverifikasi pada mode
     * STRICT, tapi tetap didaftarkan agar contoh di Postman lengkap saat mode itu dipakai.
     */
    public static List<HeaderSpec> snapTransactional() {
        return List.of(
                required("Authorization", "Bearer <accessToken>", "Token dari /v1.0/access-token/b2b"),
                required("X-PARTNER-ID", "PARTNER001", "Identitas partner (alfanumerik ≤36)"),
                required("X-EXTERNAL-ID", "20260715000000000001", "Kunci idempotensi (numerik ≤36)"),
                required("X-TIMESTAMP", "2026-07-15T10:00:00+07:00", "ISO-8601 ber-offset"),
                required("CHANNEL-ID", "95221", "Kode channel (alfanumerik ≤5)"),
                optional("X-SIGNATURE", "", "HMAC-SHA512 — wajib saat signature mode STRICT"));
    }

    /** Header Access Token B2B (Lampiran A.1) — RSA, tanpa Bearer/X-PARTNER-ID. */
    public static List<HeaderSpec> snapAccessToken() {
        return List.of(
                required("X-CLIENT-KEY", "PARTNER001", "clientId partner"),
                required("X-TIMESTAMP", "2026-07-15T10:00:00+07:00", "ISO-8601 ber-offset"),
                optional("X-SIGNATURE", "", "SHA256withRSA — wajib saat signature mode STRICT"));
    }
}

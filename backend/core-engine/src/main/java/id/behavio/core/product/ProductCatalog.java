package id.behavio.core.product;

import id.behavio.core.rule.Scenario;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Katalog satu produk simulator (design.md §3.4). Sebuah produk = satu jenis profil
 * yang berdiri sendiri: punya schema DB sendiri, port sendiri, partner sendiri, dan
 * daftar operasi SNAP sendiri. Saat ini: {@code bank} dan {@code qris}.
 *
 * Ini satu-satunya tempat mesin generik "tahu" tentang sebuah produk. Mesin di
 * :adapter-persistence dan :adapter-web ditulis SEKALI terhadap antarmuka ini, lalu
 * di-instansiasi sekali per produk — bukan dipercabangkan dengan {@code if (qris)}.
 */
public interface ProductCatalog {

    /** Kunci produk — juga dipakai sebagai nama schema PostgreSQL & segmen Admin API. */
    String key();

    /** Label untuk dashboard (mis. "Bank", "QRIS (PJP)"). */
    String label();

    List<Operation> operations();

    default Optional<Operation> byKey(String operationKey) {
        if (operationKey == null) return Optional.empty();
        String k = operationKey.trim().toLowerCase();
        return operations().stream().filter(o -> o.key().equals(k)).findFirst();
    }

    /**
     * Preset blueprint untuk (operasi, scenario) — titik awal sebelum di-override dari
     * dashboard (design.md §2 & §8). {@code Optional.empty()} = operasi ini tak punya
     * preset (logika tetap).
     */
    Optional<Scenario> blueprint(String operationKey, String scenarioName);

    /**
     * Codec aksi produk ini. Default {@link ActionCodec#NONE} — produk yang tak memutasi
     * state (QRIS) tak perlu mengimplementasikannya.
     */
    default ActionCodec actionCodec() {
        return ActionCodec.NONE;
    }

    /**
     * Contoh request body untuk sebuah operasi — dipakai export OpenAPI agar request di
     * Postman langsung siap kirim (design.md §15.5).
     *
     * Ditulis tangan di blueprint, BUKAN diturunkan dari rule/template: bentuk bersarang
     * SNAP ({@code amount: {value, currency}}) mustahil ditebak dari nama field yang
     * dibaca engine, dan contoh yang bentuknya salah lebih menyesatkan daripada tak ada.
     *
     * {@code Optional.empty()} = tak ada contoh (mis. operasi GET tanpa body, atau
     * endpoint kustom yang memang bukan milik katalog).
     */
    default Optional<Map<String, Object>> requestExample(String operationKey) {
        return Optional.empty();
    }

    /**
     * Header yang diharapkan sebuah operasi — dipakai export OpenAPI (design.md §15).
     * Default = set transaksional SNAP; produk meng-override untuk operasi yang polanya
     * berbeda (mis. {@code access-token} yang memakai {@code X-CLIENT-KEY} + RSA).
     */
    default List<HeaderSpec> requestHeaders(String operationKey) {
        return HeaderSpec.snapTransactional();
    }
}

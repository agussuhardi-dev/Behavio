package id.behavio.core.product;

import id.behavio.core.rule.Scenario;

import java.util.List;
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
}

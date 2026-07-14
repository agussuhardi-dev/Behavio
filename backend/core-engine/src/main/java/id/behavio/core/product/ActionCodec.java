package id.behavio.core.product;

import id.behavio.core.rule.Action;

import java.util.Map;
import java.util.Optional;

/**
 * Baca/tulis {@link Action} milik sebuah produk ke bentuk netral (kind + atribut string),
 * agar mesin penyimpan definisi scenario ({@code ScenarioCodec} di :adapter-persistence)
 * dapat menyimpan aksi produk apa pun tanpa mengenal tipenya.
 *
 * Atribut sengaja {@code Map<String,String>} — bukan JsonNode — supaya :core-engine tetap
 * bebas dependensi (tanpa Jackson), sesuai aturan Hexagonal di design.md §2A.
 */
public interface ActionCodec {

    /** Untuk produk tanpa aksi mutasi state sama sekali (mis. QRIS). */
    ActionCodec NONE = new ActionCodec() {
        @Override
        public Optional<Action> parse(String kind, Map<String, String> attributes) {
            return Optional.empty();
        }

        @Override
        public Optional<Encoded> encode(Action action) {
            return Optional.empty();
        }
    };

    /** {@code Optional.empty()} = kind tak dikenal produk ini (pemanggil yang menolak). */
    Optional<Action> parse(String kind, Map<String, String> attributes);

    /** {@code Optional.empty()} = action bukan milik produk ini. */
    Optional<Encoded> encode(Action action);

    record Encoded(String kind, Map<String, String> attributes) {}
}

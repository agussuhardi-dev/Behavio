package id.behavio.iso.scenario;

import id.behavio.iso.codec.IsoMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Perilaku satu operasi ISO-8583 yang bisa dipilih & diedit user.
 *
 * <p>Padanan {@code Scenario} milik produk HTTP, tapi "response"-nya adalah <b>peta DE</b>,
 * bukan body JSON.
 *
 * @param responseOverrides DE yang ditimpakan ke response alami (mis. {@code 39 → "51"})
 * @param replace           bila {@code true}, response alami dibuang dan HANYA peta ini
 *                          yang dikirim. Default {@code false} — lihat catatan di bawah.
 */
public record IsoScenario(String name,
                          Map<Integer, String> responseOverrides,
                          boolean replace,
                          IsoFault fault) {

    public IsoScenario {
        responseOverrides = responseOverrides == null ? Map.of() : Map.copyOf(responseOverrides);
        fault = fault == null ? IsoFault.none() : fault;
    }

    public static IsoScenario normal() {
        return new IsoScenario("Normal", Map.of(), false, IsoFault.none());
    }

    /**
     * Terapkan ke response alami.
     *
     * <p><b>MENIMPA, bukan mengganti</b> (kecuali {@code replace}): field korelasi seperti
     * STAN (DE11) dan RRN (DE37) wajib tetap digemakan dari request — kalau response
     * diganti utuh, peer tak bisa memasangkan balasan dengan permintaannya dan gejalanya
     * tampak seperti "host tidak menjawab".
     */
    public IsoMessage apply(IsoMessage natural) {
        if (responseOverrides.isEmpty() && !replace) {
            return natural;
        }
        IsoMessage out = replace ? new IsoMessage(natural.mti()) : copyOf(natural);
        responseOverrides.forEach(out::set);
        return out;
    }

    private static IsoMessage copyOf(IsoMessage src) {
        IsoMessage out = new IsoMessage(src.mti());
        new LinkedHashMap<>(src.fields()).forEach(out::set);
        return out;
    }
}

package id.behavio.iso.codec;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Satu pesan ISO-8583: <b>MTI + kumpulan data element</b>.
 *
 * <p>Ini padanan {@code SimRequest}/{@code SimResponse} milik produk HTTP — sengaja
 * berbeda karena ISO-8583 tak punya <i>method</i>, <i>path</i>, maupun body JSON.
 * Routing operasi ditentukan <b>MTI + processing code (DE3)</b>, bukan URL.
 *
 * <p>DE disimpan {@link TreeMap} agar urutan pack selalu menaik — bitmap ISO-8583
 * mensyaratkan itu, dan urutan acak menghasilkan pesan yang ditolak host.
 */
public final class IsoMessage {

    private final String mti;
    private final TreeMap<Integer, String> fields = new TreeMap<>();

    public IsoMessage(String mti) {
        if (mti == null || !mti.matches("\\d{4}")) {
            throw new IllegalArgumentException("MTI harus 4 digit, dapat: " + mti);
        }
        this.mti = mti;
    }

    public String mti() {
        return mti;
    }

    public IsoMessage set(int de, String value) {
        if (de < 2 || de > 128) {
            // DE1 = bitmap sekunder, dihitung otomatis saat pack — bukan diisi manual.
            throw new IllegalArgumentException("DE harus 2..128 (DE1 dihitung otomatis): " + de);
        }
        if (value != null) {
            fields.put(de, value);
        }
        return this;
    }

    public Optional<String> get(int de) {
        return Optional.ofNullable(fields.get(de));
    }

    /** Nilai DE atau {@code null} — untuk pemakaian internal yang sudah tahu risikonya. */
    public String raw(int de) {
        return fields.get(de);
    }

    public boolean has(int de) {
        return fields.containsKey(de);
    }

    /** Salinan tak-termodifikasi, urut menaik. */
    public Map<Integer, String> fields() {
        return java.util.Collections.unmodifiableMap(fields);
    }

    /**
     * MTI balasan: digit ke-3 (function) dinaikkan 1 — {@code 0200}→{@code 0210},
     * {@code 0800}→{@code 0810}, {@code 0400}→{@code 0410}.
     */
    public static String responseMti(String requestMti) {
        if (requestMti == null || !requestMti.matches("\\d{4}")) {
            throw new IllegalArgumentException("MTI harus 4 digit: " + requestMti);
        }
        int fn = Character.getNumericValue(requestMti.charAt(2));
        return requestMti.substring(0, 2) + (fn + 1) + requestMti.charAt(3);
    }

    /** Balasan kosong untuk pesan ini (MTI sudah dinaikkan). */
    public IsoMessage newResponse() {
        return new IsoMessage(responseMti(mti));
    }

    @Override
    public String toString() {
        return "IsoMessage{mti=" + mti + ", de=" + fields.keySet() + "}";
    }
}

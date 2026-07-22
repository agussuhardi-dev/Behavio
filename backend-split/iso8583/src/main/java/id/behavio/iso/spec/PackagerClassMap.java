package id.behavio.iso.spec;

import id.behavio.iso.codec.Encoding;
import id.behavio.iso.codec.FieldType;
import id.behavio.iso.codec.IsoCodecException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pemetaan nama kelas packager jPOS → semantik field kita.
 *
 * <p><b>Format-nya saja yang didukung, BUKAN pustakanya.</b> XML packager adalah bentuk
 * de-facto yang biasa diserahkan bank, jadi kita bisa menerimanya apa adanya — tapi
 * XML-nya diparse sendiri. Tidak ada dependensi jPOS, sehingga <b>AGPL v3 terhindar</b>.
 *
 * <p><b>Kelas tak dikenal DITOLAK, tidak ditebak.</b> Dokumentasi jPOS sendiri tidak
 * selalu konsisten soal lebar penanda panjang beberapa varian biner. Menebak berarti
 * pesan salah format di kabel — gejalanya menyamar jadi "host tak membalas" dan mahal
 * dilacak. Lebih baik unggahan gagal dengan menyebut nama kelasnya, lalu kelas itu
 * ditambahkan ke sini setelah dipastikan dari spec nyata.
 */
public final class PackagerClassMap {

    /** Semantik satu kelas packager: encoding + tipe + lebar penanda panjang. */
    public record Semantics(Encoding encoding, FieldType type, int lengthPrefix) {}

    private static final Map<String, Semantics> KNOWN = new LinkedHashMap<>();

    static {
        // ── ASCII, panjang tetap ────────────────────────────────────────────────
        put("IFA_NUMERIC", Encoding.ASCII, FieldType.N, 0);
        put("IFA_CHAR", Encoding.ASCII, FieldType.ANS, 0);
        put("IF_CHAR", Encoding.ASCII, FieldType.ANS, 0);
        put("IFA_BINARY", Encoding.BINARY, FieldType.B, 0);

        // ── ASCII, panjang variabel (penanda panjang dalam digit ASCII) ─────────
        put("IFA_LLNUM", Encoding.ASCII, FieldType.N, 2);
        put("IFA_LLLNUM", Encoding.ASCII, FieldType.N, 3);
        put("IFA_LLCHAR", Encoding.ASCII, FieldType.ANS, 2);
        put("IFA_LLLCHAR", Encoding.ASCII, FieldType.ANS, 3);

        // ── BCD / biner, panjang tetap ──────────────────────────────────────────
        put("IFB_NUMERIC", Encoding.BCD, FieldType.N, 0);
        put("IFB_BINARY", Encoding.BINARY, FieldType.B, 0);

        // Sengaja BELUM dipetakan (tambahkan setelah dipastikan dari spec nyata):
        //   IFB_LLNUM / IFB_LLLNUM / IFB_LLCHAR / IFB_LLLCHAR / IFB_LLBINARY …
        //     → lebar penanda panjangnya (byte BCD vs digit ASCII) dijelaskan
        //       tidak konsisten di dokumentasi; menebak = pesan rusak.
        //   IFE_* (EBCDIC) → belum ada kebutuhan; tambahkan saat ketemu.
        //   IFA_AMOUNT → membawa karakter tanda di depan, perlu aturan tersendiri.
    }

    private PackagerClassMap() {}

    private static void put(String simpleName, Encoding e, FieldType t, int prefix) {
        KNOWN.put(simpleName, new Semantics(e, t, prefix));
    }

    /**
     * @param className nama kelas dari atribut {@code class}, boleh ber-package penuh
     *                  ({@code org.jpos.iso.IFA_LLNUM}) maupun nama sederhana.
     * @throws IsoCodecException bila kelas tak dikenal — beserta daftar yang didukung.
     */
    public static Semantics require(String className) {
        if (className == null || className.isBlank()) {
            throw new IsoCodecException("Atribut class kosong pada <isofield>");
        }
        String simple = className.substring(className.lastIndexOf('.') + 1).trim();
        Semantics s = KNOWN.get(simple);
        if (s == null) {
            throw new IsoCodecException(
                    "Kelas packager belum didukung: '" + simple + "'. "
                    + "Sengaja ditolak (bukan ditebak) karena salah tafsir menghasilkan pesan "
                    + "rusak di kabel. Yang didukung saat ini: " + supported()
                    + ". Tambahkan pemetaannya di PackagerClassMap setelah dipastikan dari spec.");
        }
        return s;
    }

    public static boolean isKnown(String className) {
        if (className == null || className.isBlank()) return false;
        return KNOWN.containsKey(className.substring(className.lastIndexOf('.') + 1).trim());
    }

    /** Nama kelas yang didukung, terurut — dipakai di pesan error & dokumentasi. */
    public static Set<String> supported() {
        return new TreeSet<>(KNOWN.keySet());
    }

    /** Bitmap ditangani codec, bukan sebagai field biasa. */
    public static boolean isBitmap(String className) {
        if (className == null) return false;
        String simple = className.substring(className.lastIndexOf('.') + 1).trim();
        return simple.endsWith("BITMAP");
    }
}

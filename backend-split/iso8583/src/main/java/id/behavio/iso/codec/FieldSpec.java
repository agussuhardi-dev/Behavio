package id.behavio.iso.codec;

/**
 * Definisi satu data element ISO-8583 — <b>data, bukan kode</b>.
 *
 * <p>Spec host bank (mis. Bank Shinhan) tidak publik dan berbeda per jaringan. Karena itu
 * kamus DE berasal dari <b>profil yang di-upload</b> ({@code docs/iso8583-plan.md} §2);
 * menyesuaikan ke host baru = unggah profil, BUKAN menulis ulang codec.
 *
 * @param de           nomor data element (1–128)
 * @param name         nama manusiawi (untuk log & dashboard nanti)
 * @param type         tipe isi (n/an/ans/b/z) — menentukan arah padding
 * @param encoding     encoding di kabel (ASCII/BCD/BINARY/EBCDIC)
 * @param length       panjang tetap, ATAU panjang MAKSIMUM bila {@code lengthPrefix > 0}
 * @param lengthPrefix 0 = panjang tetap · 2 = LLVAR · 3 = LLLVAR
 */
public record FieldSpec(int de, String name, FieldType type, Encoding encoding,
                        int length, int lengthPrefix) {

    public FieldSpec {
        if (de < 1 || de > 128) {
            throw new IllegalArgumentException("Nomor DE di luar 1..128: " + de);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("DE " + de + ": panjang harus > 0");
        }
        if (lengthPrefix != 0 && lengthPrefix != 2 && lengthPrefix != 3) {
            throw new IllegalArgumentException(
                    "DE " + de + ": lengthPrefix hanya 0 (tetap), 2 (LLVAR), atau 3 (LLLVAR)");
        }
    }

    /** Panjang tetap, encoding ASCII (kasus paling lazim). */
    public static FieldSpec fixed(int de, String name, FieldType type, int length) {
        return new FieldSpec(de, name, type, Encoding.ASCII, length, 0);
    }

    /** LLVAR — panjang variabel, penanda panjang 2 digit. */
    public static FieldSpec llvar(int de, String name, FieldType type, int maxLength) {
        return new FieldSpec(de, name, type, Encoding.ASCII, maxLength, 2);
    }

    /** LLLVAR — panjang variabel, penanda panjang 3 digit. */
    public static FieldSpec lllvar(int de, String name, FieldType type, int maxLength) {
        return new FieldSpec(de, name, type, Encoding.ASCII, maxLength, 3);
    }

    /** Salinan dengan encoding berbeda — dipakai saat profil menimpa encoding. */
    public FieldSpec withEncoding(Encoding e) {
        return new FieldSpec(de, name, type, e, length, lengthPrefix);
    }

    public boolean variableLength() {
        return lengthPrefix > 0;
    }

    @Override
    public String toString() {
        String len = variableLength() ? ("L".repeat(lengthPrefix) + "VAR .." + length)
                                      : String.valueOf(length);
        return "DE" + de + " " + name + " (" + type.code() + " " + len + ", " + encoding + ")";
    }
}

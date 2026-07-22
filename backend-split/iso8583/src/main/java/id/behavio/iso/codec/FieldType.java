package id.behavio.iso.codec;

/**
 * Tipe isi data element ISO-8583.
 *
 * <p>Menentukan cara PADDING saat pack: field numerik rata-kanan diisi '0' di kiri
 * (mis. amount {@code n12} → {@code 000000010000}), sedangkan alfanumerik rata-kiri
 * diisi spasi di kanan. Salah arah padding = host asli menolak pesan, dan itu jenis bug
 * yang sulit dilacak karena panjangnya tetap "benar".
 */
public enum FieldType {
    /** Numerik — rata kanan, padding '0' di kiri. */
    N("n"),
    /** Alfabetik. */
    A("a"),
    /** Alfanumerik. */
    AN("an"),
    /** Alfanumerik + spesial. */
    ANS("ans"),
    /** Biner (hex string di representasi kita). */
    B("b"),
    /** Track data. */
    Z("z");

    private final String code;

    FieldType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Numerik & biner rata KANAN; sisanya rata KIRI. */
    public boolean padLeft() {
        return this == N || this == B;
    }

    public char padChar() {
        return padLeft() ? '0' : ' ';
    }
}

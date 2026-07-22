package id.behavio.iso.codec;

/**
 * Encoding di kabel untuk satu data element.
 *
 * <p>Per-FIELD, bukan per-pesan: satu pesan ISO-8583 lazim mencampur — mis. DE2 ASCII
 * tapi DE52 (PIN block) biner. Memaksa satu encoding untuk seluruh pesan adalah
 * penyederhanaan yang akan patah pada spec host nyata.
 */
public enum Encoding {
    /** Karakter ASCII apa adanya ("1234" = 4 byte). */
    ASCII,
    /** Binary Coded Decimal — 2 digit per byte ("1234" = 2 byte). */
    BCD,
    /** Byte mentah; direpresentasikan sebagai string hex di {@code IsoMessage}. */
    BINARY,
    /** EBCDIC — masih dijumpai di host mainframe. */
    EBCDIC
}

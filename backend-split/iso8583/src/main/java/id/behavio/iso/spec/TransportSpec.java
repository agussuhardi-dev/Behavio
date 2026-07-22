package id.behavio.iso.spec;

import id.behavio.iso.codec.IsoCodecException;

/**
 * Cara pesan dibingkai & dikodekan di kabel — bagian dari profil spec, jadi berbeda
 * per host tanpa mengubah kode.
 *
 * @param lengthPrefixBytes    lebar penanda panjang frame (2 atau 4)
 * @param lengthPrefixEncoding BINARY (network byte order) atau ASCII (digit)
 * @param charset              ASCII atau EBCDIC untuk field bertipe karakter
 * @param bitmap               BINARY (8 byte) atau HEX (16 karakter)
 */
public record TransportSpec(int lengthPrefixBytes,
                            LengthPrefixEncoding lengthPrefixEncoding,
                            CharsetKind charset,
                            BitmapEncoding bitmap) {

    public enum LengthPrefixEncoding { BINARY, ASCII }
    public enum CharsetKind { ASCII, EBCDIC }
    public enum BitmapEncoding { BINARY, HEX }

    public TransportSpec {
        if (lengthPrefixBytes != 2 && lengthPrefixBytes != 4) {
            throw new IsoCodecException(
                    "lengthPrefixBytes hanya 2 atau 4, dapat: " + lengthPrefixBytes);
        }
        if (lengthPrefixEncoding == null || charset == null || bitmap == null) {
            throw new IsoCodecException("transport: lengthPrefixEncoding/charset/bitmap wajib diisi");
        }
    }

    /** Default paling lazim: header 2-byte biner, field ASCII, bitmap 8-byte biner. */
    public static TransportSpec defaults() {
        return new TransportSpec(2, LengthPrefixEncoding.BINARY,
                CharsetKind.ASCII, BitmapEncoding.BINARY);
    }
}

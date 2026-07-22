package id.behavio.iso.codec;

/**
 * Kegagalan pack/unpack atau kamus DE tak lengkap.
 *
 * <p>Sengaja <b>gagal keras</b>, bukan mengembalikan nilai kira-kira: pesan ISO-8583 yang
 * salah format akan ditolak host asli dengan gejala yang menyesatkan ("host tak membalas",
 * "koneksi putus"), sehingga menebak jauh lebih mahal daripada berhenti dengan pesan jelas.
 */
public class IsoCodecException extends RuntimeException {

    public IsoCodecException(String message) {
        super(message);
    }

    public IsoCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}

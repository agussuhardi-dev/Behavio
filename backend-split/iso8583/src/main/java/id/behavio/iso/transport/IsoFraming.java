package id.behavio.iso.transport;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.spec.TransportSpec;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pembingkaian pesan di atas TCP.
 *
 * <p>Berbeda mendasar dari HTTP: TCP adalah <b>aliran byte tanpa batas pesan</b>, jadi
 * panjang harus dinyatakan sendiri lewat penanda di depan tiap pesan. Salah membaca
 * penanda ini membuat seluruh aliran bergeser dan semua pesan berikutnya rusak — karena
 * itu kesalahan di sini dilempar keras, bukan dipulihkan diam-diam.
 *
 * <p>Lebar & encoding penanda datang dari profil ({@link TransportSpec}), bukan konstanta.
 */
public final class IsoFraming {

    /** Batas wajar satu pesan ISO-8583; pelindung dari penanda panjang yang korup. */
    private static final int MAX_FRAME = 64 * 1024;

    private final TransportSpec transport;

    public IsoFraming(TransportSpec transport) {
        this.transport = transport;
    }

    /** @return payload satu pesan, atau {@code null} bila peer menutup koneksi dengan rapi. */
    public byte[] readFrame(DataInputStream in) throws IOException {
        int length;
        try {
            length = readLength(in);
        } catch (EOFException e) {
            return null;   // koneksi ditutup di batas pesan — normal, bukan error
        }
        if (length <= 0 || length > MAX_FRAME) {
            throw new IsoCodecException("Panjang frame tidak masuk akal: " + length
                    + " — lebar/encoding penanda panjang pada profil kemungkinan salah");
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    public void writeFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(lengthPrefix(payload.length));
        out.write(payload);
        out.flush();
    }

    private int readLength(DataInputStream in) throws IOException {
        int n = transport.lengthPrefixBytes();
        byte[] buf = new byte[n];
        in.readFully(buf);

        if (transport.lengthPrefixEncoding() == TransportSpec.LengthPrefixEncoding.ASCII) {
            String s = new String(buf, StandardCharsets.US_ASCII);
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IsoCodecException("Penanda panjang ASCII bukan angka: '" + s + "'");
            }
        }
        int len = 0;
        for (byte b : buf) {
            len = (len << 8) | (b & 0xFF);   // network byte order
        }
        return len;
    }

    private byte[] lengthPrefix(int length) {
        int n = transport.lengthPrefixBytes();
        if (transport.lengthPrefixEncoding() == TransportSpec.LengthPrefixEncoding.ASCII) {
            String s = String.format("%0" + n + "d", length);
            if (s.length() != n) {
                throw new IsoCodecException("Panjang " + length + " tak muat di " + n + " digit");
            }
            return s.getBytes(StandardCharsets.US_ASCII);
        }
        byte[] out = new byte[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = (byte) (length & 0xFF);
            length >>= 8;
        }
        return out;
    }
}

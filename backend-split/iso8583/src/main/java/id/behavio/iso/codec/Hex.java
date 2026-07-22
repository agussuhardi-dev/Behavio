package id.behavio.iso.codec;

/** Konversi hex ↔ byte. Trace dari host biasanya diserahkan sebagai hex, bukan biner. */
public final class Hex {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {}

    public static String encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(DIGITS[(b >> 4) & 0xF]).append(DIGITS[b & 0xF]);
        }
        return sb.toString();
    }

    /** Menerima spasi/newline agar trace bisa ditempel apa adanya dari dokumen. */
    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IsoCodecException("Hex kosong");
        }
        String s = hex.replaceAll("[\\s:_-]", "");
        if (s.isEmpty()) {
            throw new IsoCodecException("Hex kosong");
        }
        if (s.length() % 2 != 0) {
            throw new IsoCodecException("Panjang hex ganjil (" + s.length() + ") — trace terpotong?");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IsoCodecException(
                        "Karakter bukan hex pada posisi " + (i * 2) + ": '" + s.charAt(i * 2) + "'");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}

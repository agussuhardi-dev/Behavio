package id.behavio.iso.codec;

import id.behavio.iso.spec.ResolvedSpec;
import id.behavio.iso.spec.TransportSpec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Pack/unpack pesan ISO-8583 — <b>digerakkan profil</b>, bukan konstanta di kode.
 * Semua bentuk (kamus DE, encoding, bitmap) berasal dari {@link ResolvedSpec}, sehingga
 * host baru = unggah profil ({@code docs/iso8583-plan.md} §2).
 *
 * <p><b>MTI</b> dikodekan sebagai 4 karakter mengikuti {@code transport.charset}.
 * Host yang memakai MTI ber-BCD (2 byte) belum didukung — sengaja dibiarkan gagal keras
 * ketimbang ditebak, sejalan dengan sikap di {@code PackagerClassMap}.
 */
public final class IsoCodec {

    private static final int BITMAP_BYTES = 8;

    private final ResolvedSpec spec;

    public IsoCodec(ResolvedSpec spec) {
        this.spec = spec;
        if (spec.transport().charset() == TransportSpec.CharsetKind.EBCDIC) {
            throw new IsoCodecException("Charset EBCDIC belum didukung codec — "
                    + "tambahkan tabel konversinya sebelum memakai profil ini");
        }
    }

    // ─────────────────────────────── PACK ────────────────────────────────

    public byte[] pack(IsoMessage msg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(msg.mti().getBytes(StandardCharsets.US_ASCII));

        Map<Integer, String> fields = msg.fields();
        boolean secondary = fields.keySet().stream().anyMatch(de -> de > 64);

        byte[] bitmap = new byte[secondary ? BITMAP_BYTES * 2 : BITMAP_BYTES];
        if (secondary) {
            setBit(bitmap, 1);   // DE1 menandai adanya bitmap sekunder
        }
        for (int de : fields.keySet()) {
            if (de < 2 || de > 128) {
                throw new IsoCodecException("DE di luar 2..128: " + de);
            }
            setBit(bitmap, de);
        }
        writeBitmap(out, bitmap);

        // Urutan MENAIK itu wajib: penerima membaca field mengikuti urutan bit bitmap.
        for (Map.Entry<Integer, String> e : fields.entrySet()) {
            writeField(out, spec.dictionary().require(e.getKey()), e.getValue());
        }
        return out.toByteArray();
    }

    private void writeBitmap(ByteArrayOutputStream out, byte[] bitmap) {
        if (spec.transport().bitmap() == TransportSpec.BitmapEncoding.HEX) {
            out.writeBytes(Hex.encode(bitmap).getBytes(StandardCharsets.US_ASCII));
        } else {
            out.writeBytes(bitmap);
        }
    }

    private void writeField(ByteArrayOutputStream out, FieldSpec f, String value) {
        String v = value == null ? "" : value;

        if (f.variableLength()) {
            int len = switch (f.encoding()) {
                case BINARY -> {
                    requireHex(f, v);
                    yield v.length() / 2;      // panjang dihitung dalam BYTE
                }
                default -> v.length();
            };
            if (len > f.length()) {
                throw new IsoCodecException("DE " + f.de() + ": panjang " + len
                        + " melebihi maksimum " + f.length());
            }
            String prefix = pad(String.valueOf(len), f.lengthPrefix(), '0', true);
            out.writeBytes(prefix.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(encodeValue(f, v));
            return;
        }

        // Panjang tetap → wajib dipad ke panjang persis.
        String fixed = switch (f.encoding()) {
            case BINARY -> {
                requireHex(f, v);
                yield pad(v, f.length(), '0', true);
            }
            default -> f.type().signedAmount()
                    ? packSignedAmount(f, v)
                    : pad(v, f.length(), f.type().padChar(), f.type().padLeft());
        };
        if (visibleLength(f, fixed) > f.length()) {
            throw new IsoCodecException("DE " + f.de() + ": nilai lebih panjang dari "
                    + f.length() + " → '" + v + "'");
        }
        out.writeBytes(encodeValue(f, fixed));
    }

    /**
     * Amount bertanda (jPOS {@code IFA_AMOUNT}): tanda tetap di posisi pertama, digitnya
     * dipad '0' di kiri.
     *
     * <p>Tanda WAJIB ditulis eksplisit. Memberi default (mis. selalu 'D') akan mengubah
     * debit jadi kredit tanpa suara pada spec yang memang membedakannya — lebih baik
     * unggahan/permintaan gagal di sini daripada host asli membukukan arah yang salah.
     */
    private String packSignedAmount(FieldSpec f, String v) {
        if (f.length() < 2) {
            throw new IsoCodecException("DE " + f.de() + ": field amount bertanda butuh "
                    + "panjang minimal 2 (1 tanda + digit), di profil tertulis " + f.length());
        }
        char sign = v.isEmpty() ? ' ' : Character.toUpperCase(v.charAt(0));
        if (sign != 'C' && sign != 'D') {
            throw new IsoCodecException("DE " + f.de() + ": nilai amount bertanda harus diawali "
                    + "'C' (credit) atau 'D' (debit), mis. 'D000000010000' — dapat: '" + v + "'");
        }
        String digits = v.substring(1);
        if (!digits.chars().allMatch(Character::isDigit)) {
            throw new IsoCodecException("DE " + f.de() + ": setelah tanda harus digit — dapat: '"
                    + v + "'");
        }
        if (digits.length() > f.length() - 1) {
            throw new IsoCodecException("DE " + f.de() + ": amount " + digits.length()
                    + " digit melebihi " + (f.length() - 1) + " digit yang muat di profil");
        }
        return sign + pad(digits, f.length() - 1, '0', true);
    }

    private byte[] encodeValue(FieldSpec f, String v) {
        return switch (f.encoding()) {
            case ASCII -> v.getBytes(StandardCharsets.US_ASCII);
            case BINARY -> Hex.decode(v);
            case BCD -> packBcd(f, v);
            case EBCDIC -> throw new IsoCodecException("DE " + f.de() + ": EBCDIC belum didukung");
        };
    }

    /** BCD: 2 digit per byte; jumlah digit ganjil dipad '0' di kiri. */
    private byte[] packBcd(FieldSpec f, String v) {
        if (!v.chars().allMatch(Character::isDigit)) {
            throw new IsoCodecException("DE " + f.de() + " ber-encoding BCD tapi bukan digit: '" + v + "'");
        }
        String s = (v.length() % 2 == 0) ? v : "0" + v;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 10) << 4)
                    | Character.digit(s.charAt(i * 2 + 1), 10));
        }
        return out;
    }

    // ────────────────────────────── UNPACK ───────────────────────────────

    public IsoMessage unpack(byte[] raw) {
        if (raw == null || raw.length < 4) {
            throw new IsoCodecException("Pesan terlalu pendek untuk memuat MTI ("
                    + (raw == null ? 0 : raw.length) + " byte)");
        }
        Cursor c = new Cursor(raw);
        String mti = new String(c.take(4), StandardCharsets.US_ASCII);
        if (!mti.matches("\\d{4}")) {
            throw new IsoCodecException("MTI bukan 4 digit: '" + mti
                    + "' — apakah encoding/framing profil sudah benar?");
        }
        IsoMessage msg = new IsoMessage(mti);

        byte[] bitmap = readBitmap(c);
        boolean secondary = isSet(bitmap, 1);
        if (secondary) {
            byte[] merged = new byte[BITMAP_BYTES * 2];
            System.arraycopy(bitmap, 0, merged, 0, BITMAP_BYTES);
            System.arraycopy(readBitmap(c), 0, merged, BITMAP_BYTES, BITMAP_BYTES);
            bitmap = merged;
        }

        int max = bitmap.length * 8;
        for (int de = 2; de <= max; de++) {
            if (!isSet(bitmap, de)) {
                continue;
            }
            if (!spec.dictionary().has(de)) {
                throw new IsoCodecException("Pesan memuat DE " + de
                        + " yang tidak ada di kamus profil — profil belum sesuai host ini");
            }
            msg.set(de, readField(c, spec.dictionary().require(de)));
        }
        if (c.remaining() > 0) {
            throw new IsoCodecException("Tersisa " + c.remaining()
                    + " byte setelah semua DE terbaca — panjang field pada profil kemungkinan salah");
        }
        return msg;
    }

    private byte[] readBitmap(Cursor c) {
        if (spec.transport().bitmap() == TransportSpec.BitmapEncoding.HEX) {
            return Hex.decode(new String(c.take(BITMAP_BYTES * 2), StandardCharsets.US_ASCII));
        }
        return c.take(BITMAP_BYTES);
    }

    private String readField(Cursor c, FieldSpec f) {
        int len;
        if (f.variableLength()) {
            String prefix = new String(c.take(f.lengthPrefix()), StandardCharsets.US_ASCII);
            try {
                len = Integer.parseInt(prefix);
            } catch (NumberFormatException e) {
                throw new IsoCodecException("DE " + f.de() + ": penanda panjang bukan angka: '"
                        + prefix + "'");
            }
            if (len > f.length()) {
                throw new IsoCodecException("DE " + f.de() + ": panjang " + len
                        + " melebihi maksimum " + f.length() + " di profil");
            }
        } else {
            len = f.length();
        }

        return switch (f.encoding()) {
            case ASCII -> new String(c.take(len), StandardCharsets.US_ASCII);
            case BINARY -> Hex.encode(c.take(f.variableLength() ? len : bytesForHex(len)));
            case BCD -> unpackBcd(c.take((len + 1) / 2), len);
            case EBCDIC -> throw new IsoCodecException("DE " + f.de() + ": EBCDIC belum didukung");
        };
    }

    private String unpackBcd(byte[] bytes, int digits) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append((b >> 4) & 0xF).append(b & 0xF);
        }
        // digit ganjil → buang pad '0' di kiri
        return sb.length() > digits ? sb.substring(sb.length() - digits) : sb.toString();
    }

    // ───────────────────────────── util ──────────────────────────────────

    /** Panjang field BINER dinyatakan dalam KARAKTER hex; 2 hex = 1 byte. */
    private static int bytesForHex(int hexLength) {
        return (hexLength + 1) / 2;
    }

    private static int visibleLength(FieldSpec f, String v) {
        return f.encoding() == Encoding.BINARY ? v.length() : v.length();
    }

    private static void requireHex(FieldSpec f, String v) {
        if (!v.matches("[0-9A-Fa-f]*")) {
            throw new IsoCodecException("DE " + f.de()
                    + " ber-encoding BINARY, nilainya harus hex — dapat: '" + v + "'");
        }
    }

    private static String pad(String v, int len, char padChar, boolean left) {
        if (v.length() >= len) {
            return v;
        }
        String fill = String.valueOf(padChar).repeat(len - v.length());
        return left ? fill + v : v + fill;
    }

    /** Bit ke-{@code de} (1-based, MSB dulu). */
    private static void setBit(byte[] bitmap, int de) {
        int idx = (de - 1) / 8;
        int bit = 7 - ((de - 1) % 8);
        bitmap[idx] |= (byte) (1 << bit);
    }

    private static boolean isSet(byte[] bitmap, int de) {
        int idx = (de - 1) / 8;
        if (idx >= bitmap.length) {
            return false;
        }
        int bit = 7 - ((de - 1) % 8);
        return (bitmap[idx] & (1 << bit)) != 0;
    }

    /** Penunjuk baca dengan pesan jelas saat pesan terpotong. */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        byte[] take(int n) {
            if (pos + n > data.length) {
                throw new IsoCodecException("Pesan terpotong: butuh " + n
                        + " byte pada posisi " + pos + ", tersisa " + (data.length - pos));
            }
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }

        int remaining() {
            return data.length - pos;
        }
    }
}

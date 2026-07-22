package id.behavio.iso.codec;

import id.behavio.iso.spec.OperationRoute;
import id.behavio.iso.spec.ResolvedSpec;
import id.behavio.iso.spec.SpecProfile;
import id.behavio.iso.spec.SpecProfileResolver;
import id.behavio.iso.spec.TransportSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec ISO-8583 yang digerakkan profil. Round-trip di sini adalah dasar fitur
 * <b>uji trace</b>: tempel trace host asli → kalau ter-unpack bersih, profilnya terbukti
 * ({@code docs/iso8583-plan.md} §2 aturan 4).
 */
class IsoCodecTest {

    private static ResolvedSpec spec(TransportSpec transport, FieldSpec... fields) {
        SpecProfile p = new SpecProfile("uji", "1", null, transport, List.of(fields),
                List.of(new OperationRoute("balance-inquiry", "0200", "30")));
        return new SpecProfileResolver(n -> null).resolve(p);
    }

    private static ResolvedSpec asciiSpec() {
        return spec(TransportSpec.defaults(),
                FieldSpec.llvar(2, "PAN", FieldType.N, 19),
                FieldSpec.fixed(3, "Processing Code", FieldType.N, 6),
                FieldSpec.fixed(4, "Amount", FieldType.N, 12),
                FieldSpec.fixed(11, "STAN", FieldType.N, 6),
                FieldSpec.fixed(39, "Response Code", FieldType.AN, 2),
                FieldSpec.fixed(41, "Terminal ID", FieldType.ANS, 8),
                FieldSpec.llvar(102, "Account 1", FieldType.ANS, 28));
    }

    @Test
    @DisplayName("round-trip: cek saldo (DE bitmap primer)")
    void roundTripBalanceInquiry() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        IsoMessage req = new IsoMessage("0200")
                .set(2, "6281388370001")
                .set(3, "301000")
                .set(4, "000000000000")
                .set(11, "000123")
                .set(41, "TERM0001");

        IsoMessage back = codec.unpack(codec.pack(req));

        assertEquals("0200", back.mti());
        assertEquals("6281388370001", back.raw(2));
        assertEquals("301000", back.raw(3));
        assertEquals("000123", back.raw(11));
        assertEquals("TERM0001", back.raw(41));
    }

    @Test
    @DisplayName("round-trip lintas bitmap sekunder (DE > 64)")
    void roundTripWithSecondaryBitmap() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        IsoMessage req = new IsoMessage("0200")
                .set(3, "401000")
                .set(4, "000000075000")
                .set(102, "1234567890");

        IsoMessage back = codec.unpack(codec.pack(req));

        assertEquals("1234567890", back.raw(102), "DE102 butuh bitmap sekunder");
        assertEquals("000000075000", back.raw(4));
    }

    @Test
    @DisplayName("field numerik panjang tetap dipad '0' di KIRI")
    void numericPadsLeft() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        byte[] raw = codec.pack(new IsoMessage("0200").set(4, "75000"));
        String s = new String(raw, StandardCharsets.US_ASCII);
        assertTrue(s.contains("000000075000"), "amount harus rata kanan: " + s);
    }

    @Test
    @DisplayName("field alfanumerik dipad spasi di KANAN")
    void alphaPadsRight() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        byte[] raw = codec.pack(new IsoMessage("0200").set(41, "T1"));
        assertTrue(new String(raw, StandardCharsets.US_ASCII).contains("T1      "),
                "terminal ID harus rata kiri");
    }

    @Test
    @DisplayName("LLVAR menulis penanda panjang 2 digit")
    void llvarWritesLengthPrefix() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        byte[] raw = codec.pack(new IsoMessage("0200").set(2, "6281388370001"));
        assertTrue(new String(raw, StandardCharsets.US_ASCII).contains("136281388370001"),
                "PAN 13 digit harus diawali '13'");
    }

    @Test
    @DisplayName("bitmap HEX (gaya ASCII) juga round-trip")
    void hexBitmapRoundTrip() {
        ResolvedSpec s = spec(new TransportSpec(2, TransportSpec.LengthPrefixEncoding.BINARY,
                        TransportSpec.CharsetKind.ASCII, TransportSpec.BitmapEncoding.HEX),
                FieldSpec.fixed(3, "Proc", FieldType.N, 6),
                FieldSpec.fixed(11, "STAN", FieldType.N, 6));
        IsoCodec codec = new IsoCodec(s);
        IsoMessage back = codec.unpack(codec.pack(new IsoMessage("0800").set(3, "300000").set(11, "0007")));
        assertEquals("300000", back.raw(3));
    }

    @Test
    @DisplayName("BCD round-trip (2 digit per byte)")
    void bcdRoundTrip() {
        ResolvedSpec s = spec(TransportSpec.defaults(),
                new FieldSpec(4, "Amount", FieldType.N, Encoding.BCD, 12, 0));
        IsoCodec codec = new IsoCodec(s);
        byte[] raw = codec.pack(new IsoMessage("0200").set(4, "000000075000"));
        assertEquals("000000075000", codec.unpack(raw).raw(4));
    }

    /**
     * Inti fitur uji trace: kalau profil tak cocok dengan pesan, HARUS gagal dengan
     * pesan yang menunjuk sebabnya — bukan menghasilkan DE yang salah diam-diam.
     */
    @Test
    @DisplayName("DE di pesan yang tak ada di kamus → gagal jelas, bukan salah diam-diam")
    void unknownDeFailsLoudly() {
        // Pesan dibuat dengan profil kaya, lalu dibaca dengan profil miskin.
        IsoCodec rich = new IsoCodec(asciiSpec());
        byte[] raw = rich.pack(new IsoMessage("0200").set(3, "301000").set(41, "TERM0001"));

        IsoCodec poor = new IsoCodec(spec(TransportSpec.defaults(),
                FieldSpec.fixed(3, "Proc", FieldType.N, 6)));

        IsoCodecException e = assertThrows(IsoCodecException.class, () -> poor.unpack(raw));
        assertTrue(e.getMessage().contains("DE 41"), e.getMessage());
        assertTrue(e.getMessage().contains("profil"), e.getMessage());
    }

    @Test
    @DisplayName("panjang field salah di profil → sisa byte terdeteksi")
    void wrongLengthDetected() {
        IsoCodec writer = new IsoCodec(spec(TransportSpec.defaults(),
                FieldSpec.fixed(3, "Proc", FieldType.N, 6)));
        byte[] raw = writer.pack(new IsoMessage("0200").set(3, "301000"));

        IsoCodec reader = new IsoCodec(spec(TransportSpec.defaults(),
                FieldSpec.fixed(3, "Proc", FieldType.N, 4)));   // profil salah

        IsoCodecException e = assertThrows(IsoCodecException.class, () -> reader.unpack(raw));
        assertTrue(e.getMessage().contains("Tersisa"), e.getMessage());
    }

    @Test
    @DisplayName("pesan terpotong → pesan error menyebut posisi")
    void truncatedMessage() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        byte[] raw = codec.pack(new IsoMessage("0200").set(41, "TERM0001"));
        byte[] cut = java.util.Arrays.copyOf(raw, raw.length - 3);

        IsoCodecException e = assertThrows(IsoCodecException.class, () -> codec.unpack(cut));
        assertTrue(e.getMessage().contains("terpotong"), e.getMessage());
    }

    @Test
    @DisplayName("nilai melebihi panjang maksimum LLVAR ditolak")
    void rejectsOverlongValue() {
        IsoCodec codec = new IsoCodec(asciiSpec());
        IsoCodecException e = assertThrows(IsoCodecException.class,
                () -> codec.pack(new IsoMessage("0200").set(2, "1".repeat(25))));
        assertTrue(e.getMessage().contains("melebihi"), e.getMessage());
    }

    @Test
    @DisplayName("MTI balasan dinaikkan: 0200→0210, 0800→0810")
    void responseMti() {
        assertEquals("0210", IsoMessage.responseMti("0200"));
        assertEquals("0810", IsoMessage.responseMti("0800"));
        assertEquals("0410", IsoMessage.responseMti("0400"));
    }

    @Test
    @DisplayName("routing memakai MTI + processing code")
    void routing() {
        var s = asciiSpec();
        assertEquals("balance-inquiry",
                s.route(new IsoMessage("0200").set(3, "301000")).orElseThrow().name());
        assertTrue(s.route(new IsoMessage("0200").set(3, "401000")).isEmpty(),
                "processing code 40 tak punya rute di profil ini");
    }
}

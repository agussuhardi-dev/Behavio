package id.behavio.iso.spec;

import id.behavio.iso.codec.Encoding;
import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.FieldType;
import id.behavio.iso.codec.IsoCodecException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser profil spec dari jPOS packager XML — format yang biasanya diserahkan bank.
 * Lihat {@code docs/iso8583-plan.md} §2.
 */
class JposPackagerXmlParserTest {

    private static Map<Integer, FieldSpec> byDe(List<FieldSpec> l) {
        return l.stream().collect(Collectors.toMap(FieldSpec::de, Function.identity()));
    }

    @Test
    @DisplayName("memetakan kelas packager ke encoding, tipe, dan lebar penanda panjang")
    void mapsPackagerClasses() {
        var fields = JposPackagerXmlParser.parse("""
                <isopackager>
                  <isofield id="2"  length="19" name="PAN"             class="org.jpos.iso.IFA_LLNUM"/>
                  <isofield id="3"  length="6"  name="PROCESSING CODE" class="org.jpos.iso.IFA_NUMERIC"/>
                  <isofield id="39" length="2"  name="RESPONSE CODE"   class="org.jpos.iso.IFA_CHAR"/>
                  <isofield id="48" length="999" name="ADDITIONAL"     class="org.jpos.iso.IFA_LLLCHAR"/>
                  <isofield id="52" length="8"  name="PIN DATA"        class="org.jpos.iso.IFB_BINARY"/>
                </isopackager>
                """);
        var m = byDe(fields);
        assertEquals(5, fields.size());

        // LLVAR numerik ASCII
        assertEquals(FieldType.N, m.get(2).type());
        assertEquals(Encoding.ASCII, m.get(2).encoding());
        assertEquals(2, m.get(2).lengthPrefix());
        assertEquals(19, m.get(2).length());

        // panjang tetap
        assertEquals(0, m.get(3).lengthPrefix());
        assertEquals(FieldType.N, m.get(3).type());

        // LLLVAR karakter
        assertEquals(3, m.get(48).lengthPrefix());
        assertEquals(FieldType.ANS, m.get(48).type());

        // biner
        assertEquals(Encoding.BINARY, m.get(52).encoding());
        assertEquals(FieldType.B, m.get(52).type());
    }

    /**
     * Inti keputusan desain: kelas yang belum dipastikan semantiknya HARUS ditolak.
     * Menebak berarti pesan salah format di kabel — gejalanya menyamar jadi "host tak
     * membalas", jenis kegagalan yang paling mahal dilacak.
     */
    @Test
    @DisplayName("kelas packager tak dikenal DITOLAK, bukan ditebak")
    void rejectsUnknownPackagerClass() {
        IsoCodecException e = assertThrows(IsoCodecException.class, () ->
                JposPackagerXmlParser.parse("""
                        <isopackager>
                          <isofield id="2" length="19" name="PAN" class="org.jpos.iso.IFB_LLLNUM"/>
                        </isopackager>
                        """));
        assertTrue(e.getMessage().contains("IFB_LLLNUM"), "pesan harus menyebut kelasnya: " + e.getMessage());
        assertTrue(e.getMessage().contains("didukung"), "pesan harus menyebut yang didukung: " + e.getMessage());
    }

    /** MTI (id 0) & bitmap bukan field biasa — ditangani codec, jadi dilewati. */
    @Test
    @DisplayName("MTI dan bitmap dilewati, bukan jadi data element")
    void skipsMtiAndBitmap() {
        var fields = JposPackagerXmlParser.parse("""
                <isopackager>
                  <isofield id="0" length="4"  name="MTI"    class="org.jpos.iso.IFA_NUMERIC"/>
                  <isofield id="1" length="16" name="BITMAP" class="org.jpos.iso.IFA_BITMAP"/>
                  <isofield id="3" length="6"  name="PROC"   class="org.jpos.iso.IFA_NUMERIC"/>
                </isopackager>
                """);
        assertEquals(List.of(3), fields.stream().map(FieldSpec::de).toList());
    }

    @Test
    @DisplayName("DE ganda ditolak")
    void rejectsDuplicateDe() {
        IsoCodecException e = assertThrows(IsoCodecException.class, () ->
                JposPackagerXmlParser.parse("""
                        <isopackager>
                          <isofield id="3" length="6" name="A" class="org.jpos.iso.IFA_NUMERIC"/>
                          <isofield id="3" length="6" name="B" class="org.jpos.iso.IFA_NUMERIC"/>
                        </isopackager>
                        """));
        assertTrue(e.getMessage().contains("lebih dari sekali"), e.getMessage());
    }

    @Test
    @DisplayName("XML kosong / bukan packager ditolak dengan pesan jelas")
    void rejectsGarbage() {
        assertThrows(IsoCodecException.class, () -> JposPackagerXmlParser.parse(""));
        assertThrows(IsoCodecException.class, () -> JposPackagerXmlParser.parse("<html><body/></html>"));
        assertThrows(IsoCodecException.class, () -> JposPackagerXmlParser.parse("bukan xml"));
    }

    /**
     * Berkas packager jPOS lazim memuat DOCTYPE ber-SYSTEM ke genericpackager.dtd.
     * Berkas ini datang dari UNGGAHAN, jadi entitas eksternal harus mati (XXE) — tapi
     * berkas yang sah tetap harus bisa diparse.
     */
    @Test
    @DisplayName("DOCTYPE eksternal tidak menggagalkan parse & tidak dimuat (aman XXE)")
    void handlesDoctypeSafely() {
        var fields = JposPackagerXmlParser.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE isopackager SYSTEM "genericpackager.dtd">
                <isopackager>
                  <isofield id="3" length="6" name="PROC" class="org.jpos.iso.IFA_NUMERIC"/>
                </isopackager>
                """);
        assertEquals(1, fields.size());
        assertEquals(3, fields.get(0).de());
    }

    @Test
    @DisplayName("nama kelas boleh sederhana tanpa package")
    void acceptsSimpleClassName() {
        var fields = JposPackagerXmlParser.parse("""
                <isopackager>
                  <isofield id="4" length="12" name="AMOUNT" class="IFA_NUMERIC"/>
                </isopackager>
                """);
        assertEquals(FieldType.N, fields.get(0).type());
    }
}

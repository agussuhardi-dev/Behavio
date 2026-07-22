package id.behavio.iso.spec;

import id.behavio.iso.codec.Encoding;
import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.FieldType;
import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.codec.IsoMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warisan profil ({@code extends}) — inti dari multi-profile: spec bank di dunia nyata
 * adalah "standar, kecuali N field ini". Lihat {@code docs/iso8583-plan.md} §2.
 */
class SpecProfileResolverTest {

    private final Map<String, SpecProfile> store = new HashMap<>();
    private final SpecProfileResolver resolver = new SpecProfileResolver(store::get);

    private SpecProfile base() {
        SpecProfile p = new SpecProfile("iso-1987", "1.0", null, TransportSpec.defaults(),
                List.of(FieldSpec.llvar(2, "PAN", FieldType.N, 19),
                        FieldSpec.fixed(3, "Processing Code", FieldType.N, 6),
                        FieldSpec.fixed(39, "Response Code", FieldType.AN, 2)),
                List.of(new OperationRoute("balance-inquiry", "0200", "30")));
        store.put(p.name(), p);
        return p;
    }

    @Test
    @DisplayName("anak hanya deklarasi yang BEDA; sisanya diwarisi")
    void childOverridesOnlyDeclaredFields() {
        base();
        SpecProfile shinhan = new SpecProfile("shinhan", "1.0", "iso-1987", null,
                List.of(FieldSpec.lllvar(54, "Additional Amounts", FieldType.AN, 120),
                        FieldSpec.fixed(39, "Response Code", FieldType.AN, 3)), // ditimpa
                List.of());

        var r = resolver.resolve(shinhan);

        assertEquals(19, r.dictionary().require(2).length(), "DE2 diwarisi apa adanya");
        assertEquals(3, r.dictionary().require(39).length(), "DE39 ditimpa anak");
        assertEquals(120, r.dictionary().require(54).length(), "DE54 tambahan anak");
        assertEquals(java.util.Set.of(2, 3, 39, 54), r.dictionary().defined());
    }

    @Test
    @DisplayName("transport & operations diwarisi bila anak tak mendeklarasikannya")
    void inheritsTransportAndOperations() {
        base();
        SpecProfile child = new SpecProfile("anak", "1.0", "iso-1987", null,
                List.of(FieldSpec.fixed(4, "Amount", FieldType.N, 12)), List.of());

        var r = resolver.resolve(child);

        assertEquals(2, r.transport().lengthPrefixBytes());
        assertEquals(1, r.operations().size());
        assertEquals("balance-inquiry", r.operations().get(0).name());
    }

    @Test
    @DisplayName("transport anak menang atas induk")
    void childTransportWins() {
        base();
        SpecProfile child = new SpecProfile("anak", "1.0", "iso-1987",
                new TransportSpec(4, TransportSpec.LengthPrefixEncoding.ASCII,
                        TransportSpec.CharsetKind.ASCII, TransportSpec.BitmapEncoding.HEX),
                List.of(FieldSpec.fixed(4, "Amount", FieldType.N, 12)), List.of());

        var r = resolver.resolve(child);
        assertEquals(4, r.transport().lengthPrefixBytes());
        assertEquals(TransportSpec.BitmapEncoding.HEX, r.transport().bitmap());
    }

    /** Tanpa deteksi ini prosesnya berputar sampai StackOverflowError — pesan yang tak menolong. */
    @Test
    @DisplayName("warisan melingkar ditolak dengan menyebut lintasannya")
    void rejectsCircularInheritance() {
        SpecProfile a = new SpecProfile("A", "1", "B", null,
                List.of(FieldSpec.fixed(3, "x", FieldType.N, 6)), List.of());
        SpecProfile b = new SpecProfile("B", "1", "A", null,
                List.of(FieldSpec.fixed(4, "y", FieldType.N, 6)), List.of());
        store.put("A", a);
        store.put("B", b);

        IsoCodecException e = assertThrows(IsoCodecException.class, () -> resolver.resolve(a));
        assertTrue(e.getMessage().contains("melingkar"), e.getMessage());
    }

    @Test
    @DisplayName("induk yang tak ada ditolak, bukan diam-diam diabaikan")
    void rejectsMissingParent() {
        SpecProfile orphan = new SpecProfile("yatim", "1", "tidak-ada", null,
                List.of(FieldSpec.fixed(3, "x", FieldType.N, 6)), List.of());
        IsoCodecException e = assertThrows(IsoCodecException.class, () -> resolver.resolve(orphan));
        assertTrue(e.getMessage().contains("tidak ditemukan"), e.getMessage());
    }

    @Test
    @DisplayName("routing: rute ber-processingCode menang atas rute MTI saja")
    void specificRouteWinsOverGeneric() {
        SpecProfile p = new SpecProfile("r", "1", null, TransportSpec.defaults(),
                List.of(FieldSpec.fixed(3, "Proc", FieldType.N, 6)),
                List.of(new OperationRoute("generic-0200", "0200", null),
                        new OperationRoute("transfer", "0200", "40")));
        var r = resolver.resolve(p);

        IsoMessage transfer = new IsoMessage("0200").set(3, "401000");
        assertEquals("transfer", r.route(transfer).orElseThrow().name());

        IsoMessage other = new IsoMessage("0200").set(3, "990000");
        assertEquals("generic-0200", r.route(other).orElseThrow().name());
    }

    @Test
    @DisplayName("rute bertabrakan ditolak saat profil dibuat")
    void rejectsConflictingRoutes() {
        IsoCodecException e = assertThrows(IsoCodecException.class, () ->
                new SpecProfile("x", "1", null, TransportSpec.defaults(),
                        List.of(FieldSpec.fixed(3, "Proc", FieldType.N, 6)),
                        List.of(new OperationRoute("a", "0200", "30"),
                                new OperationRoute("b", "0200", "30"))));
        assertTrue(e.getMessage().contains("bertabrakan"), e.getMessage());
    }

    @Test
    @DisplayName("profil tanpa field dan tanpa induk ditolak")
    void rejectsEmptyStandaloneProfile() {
        assertThrows(IsoCodecException.class, () ->
                new SpecProfile("kosong", "1", null, TransportSpec.defaults(), List.of(), List.of()));
    }

    @Test
    @DisplayName("JSON round-trip mempertahankan field, encoding, dan rute")
    void jsonRoundTrip() {
        SpecProfile p = new SpecProfile("shinhan", "2.0", "iso-1987",
                new TransportSpec(2, TransportSpec.LengthPrefixEncoding.BINARY,
                        TransportSpec.CharsetKind.ASCII, TransportSpec.BitmapEncoding.BINARY),
                List.of(new FieldSpec(52, "PIN", FieldType.B, Encoding.BINARY, 16, 0)),
                List.of(new OperationRoute("transfer", "0200", "40")));

        SpecProfile back = SpecProfileJson.read(SpecProfileJson.write(p));

        assertEquals("shinhan", back.name());
        assertEquals("2.0", back.version());
        assertEquals("iso-1987", back.parent());
        assertEquals(Encoding.BINARY, back.fields().get(0).encoding());
        assertEquals(FieldType.B, back.fields().get(0).type());
        assertEquals("40", back.operations().get(0).processingCode());
    }
}

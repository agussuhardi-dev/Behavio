package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldDictionary;
import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.IsoCodecException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profil spec ISO-8583 satu host — <b>data yang di-upload, bukan kode</b>
 * ({@code docs/iso8583-plan.md} §2).
 *
 * <p>Boleh <b>mewarisi</b> profil lain lewat {@link #parent()}: spec bank di dunia nyata
 * hampir selalu "ISO 8583 standar, KECUALI N field ini", jadi profil turunan cukup
 * mendeklarasikan yang berbeda. Pola yang sama dengan blueprint→override di produk HTTP.
 *
 * <p>Objek ini adalah bentuk <i>mentah</i> (belum digabung dengan induknya) — penggabungan
 * dilakukan {@link SpecProfileResolver}.
 *
 * @param name       nama profil, unik bersama {@code version}
 * @param version    versi; unggahan baru = versi BARU, tidak menimpa (immutable)
 * @param parent     nama profil induk yang diwarisi, atau {@code null}
 * @param transport  framing/encoding; {@code null} = warisi induk / default
 * @param fields     DE yang dideklarasikan profil ini (menimpa induk per-DE)
 * @param operations rute operasi; kosong = warisi induk
 */
public record SpecProfile(String name,
                          String version,
                          String parent,
                          TransportSpec transport,
                          List<FieldSpec> fields,
                          List<OperationRoute> operations) {

    public SpecProfile {
        if (name == null || name.isBlank()) {
            throw new IsoCodecException("Profil: 'name' wajib diisi");
        }
        if (version == null || version.isBlank()) {
            throw new IsoCodecException("Profil '" + name + "': 'version' wajib diisi");
        }
        name = name.trim();
        version = version.trim();
        parent = (parent == null || parent.isBlank()) ? null : parent.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        operations = operations == null ? List.of() : List.copyOf(operations);

        // Profil tanpa induk harus berdiri sendiri — kalau tidak, ia tak akan pernah
        // bisa mem-pack apa pun dan kegagalannya baru terlihat jauh di runtime.
        if (parent == null && fields.isEmpty()) {
            throw new IsoCodecException(
                    "Profil '" + name + "' tak punya field dan tak mewarisi profil lain — "
                    + "isi 'fields' atau tentukan 'extends'");
        }
        requireNoDuplicateDe(name, fields);
        requireNoConflictingRoutes(name, operations);
    }

    private static void requireNoDuplicateDe(String name, List<FieldSpec> fields) {
        Map<Integer, Boolean> seen = new HashMap<>();
        for (FieldSpec f : fields) {
            if (seen.put(f.de(), Boolean.TRUE) != null) {
                throw new IsoCodecException(
                        "Profil '" + name + "': DE " + f.de() + " didefinisikan lebih dari sekali");
            }
        }
    }

    /**
     * Dua rute dengan MTI+processingCode sama membuat pesan yang sama bisa jatuh ke dua
     * operasi. Ambiguitas semacam ini harus gagal saat UNGGAH — kalau lolos, ia muncul
     * sebagai "kadang jawabannya beda" yang sangat mahal dilacak.
     */
    private static void requireNoConflictingRoutes(String name, List<OperationRoute> ops) {
        Map<String, String> byKey = new HashMap<>();
        for (OperationRoute r : ops) {
            String prev = byKey.put(r.key(), r.name());
            if (prev != null) {
                throw new IsoCodecException("Profil '" + name + "': rute bertabrakan — operasi '"
                        + prev + "' dan '" + r.name() + "' sama-sama menangani " + r.key());
            }
        }
    }

    public String id() {
        return name + ":" + version;
    }

    /** Kamus DE profil ini SENDIRI (tanpa warisan) — dipakai resolver. */
    public FieldDictionary ownDictionary() {
        return FieldDictionary.of(new ArrayList<>(fields));
    }
}

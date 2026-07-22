package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.Hex;
import id.behavio.iso.codec.IsoCodec;
import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.codec.IsoMessage;
import id.behavio.iso.persistence.SpecProfileRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unggah, validasi, dan <b>uji trace</b> profil spec.
 *
 * <p>Uji trace adalah inti nilai fitur ini: spec host bank tak bisa diverifikasi dari
 * dokumen mana pun, tapi kalau satu trace NYATA ter-unpack bersih memakai profil, profil
 * itu <b>terbukti</b>, bukan diasumsikan ({@code docs/iso8583-plan.md} §2 aturan 4).
 */
@Service
public class SpecProfileService {

    private final SpecProfileRepository repo;

    public SpecProfileService(SpecProfileRepository repo) {
        this.repo = repo;
    }

    private SpecProfileResolver resolver() {
        return new SpecProfileResolver(name -> repo.findLatest(name).orElse(null));
    }

    /** Profil yang sudah digabung dengan induknya, siap dipakai codec. */
    public ResolvedSpec resolve(String name, String version) {
        SpecProfile p = repo.find(name, version).orElseThrow(() ->
                new IsoCodecException("Profil '" + name + "' versi '" + version + "' tidak ada"));
        return resolver().resolve(p);
    }

    /**
     * Unggah dari JSON.
     *
     * <p>Divalidasi DUA lapis: bentuknya (saat objek dibuat) dan <b>keterpakaiannya</b> —
     * profil di-resolve lalu codec dibuat. Profil yang lolos simpan tapi tak bisa dipakai
     * codec hanya memindahkan kegagalan ke saat host asli sudah menunggu balasan.
     */
    public UUID uploadJson(String json) {
        SpecProfile p = SpecProfileJson.read(json);
        validateUsable(p);
        return repo.save(p, "JSON");
    }

    /**
     * Unggah dari jPOS packager XML. Berkas packager hanya memuat daftar field, jadi
     * nama/versi/extends/operasi diberikan terpisah.
     */
    public UUID uploadPackagerXml(String xml, String name, String version,
                                  String parent, List<OperationRoute> operations) {
        JposPackagerXmlParser.Parsed parsed = JposPackagerXmlParser.parse(xml);
        // Berkas packager hanya menyimpan satu petunjuk transport: kelas bitmap. Sisanya
        // (lebar header, charset) diwarisi induk — jangan sampai ikut tertimpa bawaan.
        TransportSpec transport = null;
        if (parsed.bitmap() != null) {
            TransportSpec base = parent == null || parent.isBlank()
                    ? TransportSpec.defaults()
                    : resolver().resolve(repo.findLatest(parent).orElseThrow(() ->
                            new IsoCodecException("Profil induk '" + parent + "' tidak ada")))
                              .transport();
            transport = new TransportSpec(base.lengthPrefixBytes(), base.lengthPrefixEncoding(),
                    base.charset(), parsed.bitmap());
        }
        SpecProfile p = new SpecProfile(name, version, parent, transport, parsed.fields(),
                operations == null ? List.of() : operations);
        validateUsable(p);
        return repo.save(p, "XML");
    }

    /** Gagal SEKARANG (saat unggah) bila profil tak bisa dipakai, bukan nanti di kabel. */
    private void validateUsable(SpecProfile p) {
        ResolvedSpec resolved = resolver().resolve(p);
        new IsoCodec(resolved);   // menolak EBCDIC dsb.
        if (resolved.dictionary().defined().isEmpty()) {
            throw new IsoCodecException("Profil '" + p.name() + "' tak menghasilkan satu pun DE");
        }
    }

    /** Hasil uji trace: DE yang berhasil dibaca, atau sebab kegagalan yang menunjuk. */
    public record TraceResult(boolean ok, String mti, String operation,
                              Map<String, String> fields, String error) {}

    /**
     * Tempel trace hex dari host asli → dibaca memakai profil. Inilah cara membuktikan
     * profil tanpa dokumen: kalau bersih, isi DE-nya terlihat dan bisa dinilai masuk akal;
     * kalau gagal, pesannya menunjuk DE/posisi yang bermasalah.
     */
    public TraceResult testTrace(String name, String version, String hex) {
        ResolvedSpec spec;
        try {
            spec = resolve(name, version);
        } catch (RuntimeException e) {
            return new TraceResult(false, null, null, Map.of(), e.getMessage());
        }
        try {
            IsoMessage msg = new IsoCodec(spec).unpack(Hex.decode(hex));
            Map<String, String> out = new LinkedHashMap<>();
            msg.fields().forEach((de, v) -> {
                String label = spec.dictionary().has(de) ? spec.dictionary().require(de).name() : "?";
                out.put("DE" + de + " (" + label + ")", v);
            });
            String op = spec.route(msg).map(OperationRoute::name).orElse(null);
            return new TraceResult(true, msg.mti(), op, out, null);
        } catch (RuntimeException e) {
            return new TraceResult(false, null, null, Map.of(), e.getMessage());
        }
    }
}

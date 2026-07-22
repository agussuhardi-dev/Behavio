package id.behavio.iso.web;

import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.persistence.SpecProfileRepository;
import id.behavio.iso.spec.OperationRoute;
import id.behavio.iso.spec.PackagerClassMap;
import id.behavio.iso.spec.ResolvedSpec;
import id.behavio.iso.spec.SpecProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API profil spec ISO-8583 ({@code docs/iso8583-plan.md} §2).
 *
 * <p>Path memakai segmen produk ber-constraint {@code {product:iso8583}} — pola yang sama
 * dengan bank/qris, supaya dua salinan controller tak pernah bertabrakan pola path saat
 * dimuat bersama di main-app.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:iso8583}/spec-profiles")
public class SpecProfileAdminController {

    private final SpecProfileService service;
    private final SpecProfileRepository repo;

    public SpecProfileAdminController(SpecProfileService service, SpecProfileRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** Daftar profil (ringkas, tanpa definisi penuh). */
    @GetMapping
    public List<SpecProfileRepository.Summary> list() {
        return repo.list();
    }

    /** Kelas packager jPOS yang didukung — dipakai saat menyiapkan berkas unggahan. */
    @GetMapping("/supported-packager-classes")
    public Map<String, Object> supportedClasses() {
        return Map.of(
                "supported", PackagerClassMap.supported(),
                "catatan", "Kelas di luar daftar ini DITOLAK saat unggah, bukan ditebak — "
                        + "salah tafsir menghasilkan pesan rusak di kabel.");
    }

    /** Detail profil setelah digabung dengan induknya (bentuk yang dipakai runtime). */
    @GetMapping("/{name}/{version}")
    public Map<String, Object> detail(@PathVariable String name, @PathVariable String version) {
        ResolvedSpec s = service.resolve(name, version);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int de : s.dictionary().defined()) {
            FieldSpec f = s.dictionary().require(de);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("de", f.de());
            m.put("name", f.name());
            m.put("type", f.type().code());
            m.put("encoding", f.encoding().name());
            m.put("length", f.length());
            m.put("lengthPrefix", f.lengthPrefix());
            fields.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("transport", Map.of(
                "lengthPrefixBytes", s.transport().lengthPrefixBytes(),
                "lengthPrefixEncoding", s.transport().lengthPrefixEncoding().name(),
                "charset", s.transport().charset().name(),
                "bitmap", s.transport().bitmap().name()));
        out.put("fields", fields);
        out.put("operations", s.operations().stream().map(r -> Map.of(
                "name", r.name(), "mti", r.mti(),
                "processingCode", r.processingCode() == null ? "" : r.processingCode())).toList());
        return out;
    }

    /** Unggah profil berformat JSON. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadJson(@RequestBody String json) {
        return created(service.uploadJson(json));
    }

    /**
     * Unggah <b>jPOS packager XML</b> — bentuk yang biasanya diserahkan bank. Berkas itu
     * hanya memuat daftar field, jadi identitas & rute operasi diberikan lewat query param.
     */
    @PostMapping(consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
                              MediaType.TEXT_PLAIN_VALUE })
    public ResponseEntity<?> uploadXml(@RequestBody String xml,
                                       @RequestParam String name,
                                       @RequestParam String version,
                                       @RequestParam(required = false) String parent,
                                       @RequestParam(required = false) List<String> operation) {
        return created(service.uploadPackagerXml(xml, name, version, parent, routes(operation)));
    }

    /**
     * Uji trace: tempel hex dari host asli, lihat apakah profil membacanya dengan benar.
     * Balasan 200 walau gagal parse — kegagalan di sini adalah HASIL uji, bukan error API.
     */
    /**
     * Menghapus satu versi profil — DITOLAK bila masih ada yang memakainya.
     *
     * <p>Menghapus profil yang masih ditunjuk simulator akan membuat simulator itu gagal
     * start dengan pesan yang menyesatkan (tampak seperti kerusakan, padahal ulah
     * penghapusan). Karena itu pemakainya disebutkan, bukan sekadar ditolak.
     */
    @DeleteMapping("/{name}/{version}")
    public ResponseEntity<?> delete(@PathVariable String name, @PathVariable String version) {
        List<String> pemakai = repo.dependents(name, version);
        if (!pemakai.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Profil '" + name + "' v" + version + " masih dipakai: "
                            + String.join(", ", pemakai)
                            + ". Hapus/alihkan pemakainya dulu.",
                    "dependents", pemakai));
        }
        if (!repo.delete(name, version)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Profil '" + name + "' v" + version + " tidak ada"));
        }
        return ResponseEntity.ok(Map.of("status", "deleted", "name", name, "version", version));
    }

    @PostMapping(value = "/{name}/{version}/test-trace", consumes = MediaType.TEXT_PLAIN_VALUE)
    public SpecProfileService.TraceResult testTrace(@PathVariable String name,
                                                    @PathVariable String version,
                                                    @RequestBody String hex) {
        return service.testTrace(name, version, hex);
    }

    /** {@code operation=nama:mti[:processingCode]} — mis. {@code transfer:0200:40}. */
    private static List<OperationRoute> routes(List<String> raw) {
        List<OperationRoute> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            String[] p = s.split(":");
            if (p.length < 2) {
                throw new IsoCodecException("Format operation salah: '" + s
                        + "' — pakai nama:mti[:processingCode], mis. transfer:0200:40");
            }
            out.add(new OperationRoute(p[0], p[1], p.length > 2 ? p[2] : null));
        }
        return out;
    }

    private static ResponseEntity<?> created(java.util.UUID id) {
        return ResponseEntity.ok(Map.of("id", id, "status", "saved"));
    }

    /** Kegagalan validasi/parse profil = 400, bukan 500 — ini kesalahan berkas pengguna. */
    @ExceptionHandler(IsoCodecException.class)
    public ResponseEntity<?> handle(IsoCodecException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

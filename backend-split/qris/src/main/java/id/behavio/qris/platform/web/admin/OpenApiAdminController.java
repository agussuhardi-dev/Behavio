package id.behavio.qris.platform.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import id.behavio.qris.platform.web.ProductRegistry;
import id.behavio.qris.platform.web.PublicHost;
import id.behavio.qris.platform.web.openapi.OpenApiExporter;
import id.behavio.qris.platform.web.openapi.OpenApiImporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API export/import OpenAPI (design.md §15) — generik lintas produk seperti
 * controller admin lain: yang berbeda cuma runtime yang dipilih dari {@code {product}}.
 *
 * <pre>
 * GET  .../openapi?format=yaml|json   unduh spec
 * POST .../openapi/preview            pratinjau (TIDAK mengubah apa pun)
 * POST .../openapi/import             terapkan sesuai pemetaan user
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/v1/{product:qris}/simulators/{id}/openapi")
public class OpenApiAdminController {

    private final ProductRegistry products;
    private final OpenApiExporter exporter = new OpenApiExporter();
    private final OpenApiImporter importer = new OpenApiImporter();

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private final PublicHost publicHost;

    public OpenApiAdminController(ProductRegistry products, PublicHost publicHost) {
        this.products = products;
        this.publicHost = publicHost;
    }

    /** Unduh spec. Default YAML: bentuk lazim OpenAPI, dan Postman menerima keduanya. */
    @GetMapping
    public ResponseEntity<String> export(@PathVariable String product,
                                         @PathVariable UUID id,
                                         @RequestParam(defaultValue = "yaml") String format) throws Exception {
        var runtime = products.require(product);
        Map<String, Object> doc = exporter.export(runtime, id, publicHost.resolve());

        boolean asJson = "json".equalsIgnoreCase(format);
        String body = asJson
                ? json.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                : yaml.writeValueAsString(doc);

        String name = runtime.admin().find(id).map(s -> slug(s.name())).orElse("simulator");
        String filename = "behavio-" + product + "-" + name + (asJson ? ".json" : ".yaml");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(asJson ? MediaType.APPLICATION_JSON : MediaType.valueOf("application/yaml"))
                .body(body);
    }

    /**
     * Pratinjau. Body = spec mentah (text/plain agar dashboard bisa mengirim isi file apa
     * adanya, YAML maupun JSON, tanpa membungkusnya lebih dulu).
     */
    @PostMapping(value = "/preview", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> preview(@PathVariable String product,
                                     @PathVariable UUID id,
                                     @RequestBody String spec) {
        try {
            return ResponseEntity.ok(importer.preview(products.require(product), spec));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/import")
    public ResponseEntity<?> importSpec(@PathVariable String product,
                                        @PathVariable UUID id,
                                        @RequestBody ImportRequest request) {
        if (request == null || request.spec() == null || request.spec().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "spec wajib diisi"));
        }
        List<OpenApiImporter.Mapping> mappings = new ArrayList<>();
        for (MappingRequest m : request.mappings() == null ? List.<MappingRequest>of() : request.mappings()) {
            OpenApiImporter.Action action;
            try {
                action = OpenApiImporter.Action.valueOf(m.action().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "action tak dikenal: '" + m.action() + "' (CATALOG|CUSTOM|SKIP)"));
            }
            mappings.add(new OpenApiImporter.Mapping(m.path(), m.method(), action, m.operation()));
        }
        try {
            return ResponseEntity.ok(importer.apply(products.require(product), id, request.spec(), mappings));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record MappingRequest(String path, String method, String action, String operation) {}

    public record ImportRequest(String spec, List<MappingRequest> mappings) {}

    /** Nama simulator → potongan aman untuk nama file unduhan. */
    private static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("(^-|-$)", "");
        return s.isBlank() ? "simulator" : s;
    }
}

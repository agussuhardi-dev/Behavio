package id.behavio.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.core.engine.ResponseRenderer;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.ScenarioConfigPort;
import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.product.HeaderSpec;
import id.behavio.core.product.ProductCatalog;
import id.behavio.web.ProductRuntime;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulator → dokumen OpenAPI 3.0.3 (design.md §15).
 *
 * Dua pembaca sekaligus:
 * <ul>
 *   <li><b>Tool luar</b> (Postman/Swagger/Insomnia) membaca bagian OpenAPI biasa —
 *       path, header, requestBody, contoh response tiap scenario.</li>
 *   <li><b>Behavio sendiri</b> membaca {@code x-behavio} saat import, sehingga
 *       {@code export → import} memulihkan perilaku utuh. Spec OpenAPI mewajibkan tool
 *       mengabaikan field berawalan {@code x-}, jadi ini tak merusak interop.</li>
 * </ul>
 *
 * Perilaku dibawa apa adanya sebagai JSON {@code ScenarioCodec} lewat
 * {@link ScenarioConfigPort#effectiveDefinition} — exporter ini <b>tak mengenal AST
 * rule sama sekali</b>, hanya memindahkan blob. Tak ada codec kedua yang harus dijaga
 * sinkron dengan yang di persistence.
 *
 * <b>Sengaja TIDAK diekspor</b> (design.md §15.3): partner + {@code client_secret}/
 * {@code public_key} (kredensial — file ini dibuat untuk dibagikan) dan saldo/transaksi
 * (state, bukan deskripsi API).
 */
public class OpenApiExporter {

    /** Kunci extension. Satu tempat, agar exporter & importer tak pernah beda ejaan. */
    public static final String X_BEHAVIO = "x-behavio";

    private static final String OPENAPI_VERSION = "3.0.3";

    /**
     * Nama sintetis untuk operasi "berlogika tetap" ({@code Operation.scenarioNames}
     * kosong — mis. seluruh QRIS selain generate). Operasi itu sengaja tak punya baris
     * scenario di DB: responsnya memang tak dipilih lewat sakelar. Tanpa ini mereka
     * diekspor tanpa satu pun contoh response — justru bagian yang paling ingin dilihat
     * di Postman.
     *
     * Aman karena {@code ScenarioConfigPort} jatuh ke blueprint saat tak ada definisi
     * custom, dan menurut kontrak {@link id.behavio.core.product.Operation} blueprint
     * operasi tanpa scenario tidak bergantung pada nama. Operasi yang memang tak punya
     * blueprint (mis. access-token QRIS) gagal dan dilewati diam-diam.
     */
    private static final String DEFAULT_SCENARIO = "Default";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseRenderer renderer = new ResponseRenderer();

    public Map<String, Object> export(ProductRuntime runtime, UUID simulatorId) {
        SimulatorAdmin.SimulatorView sim = runtime.admin().find(simulatorId)
                .orElseThrow(() -> new IllegalArgumentException("Simulator tidak ditemukan: " + simulatorId));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", OPENAPI_VERSION);
        doc.put("info", info(sim, runtime.catalog()));
        doc.put("servers", servers(sim));
        doc.put("paths", paths(runtime, simulatorId));
        // Tanpa components.securitySchemes: Authorization/X-CLIENT-KEY sudah didaftarkan
        // eksplisit sebagai header parameter oleh katalog (HeaderSpec). Mendeklarasikannya
        // dua kali hanya membuat Postman menampilkan auth ganda yang saling bertentangan.
        doc.put(X_BEHAVIO, rootExtension(sim, runtime.catalog()));
        return doc;
    }

    private Map<String, Object> info(SimulatorAdmin.SimulatorView sim, ProductCatalog catalog) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", sim.name() + " — " + catalog.label());
        info.put("version", "1.0.0");
        info.put("description", "Diekspor dari Behavio (simulator " + catalog.label() + ", port "
                + sim.port() + ").\n\n"
                + "Perilaku (rule, scenario, fault, webhook) dibawa di extension `x-behavio` dan "
                + "diabaikan tool lain — impor kembali ke Behavio untuk memulihkannya.\n\n"
                + "Kredensial partner dan state (saldo/transaksi) TIDAK termasuk.");
        return info;
    }

    private List<Map<String, Object>> servers(SimulatorAdmin.SimulatorView sim) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", "http://localhost:" + sim.port());
        server.put("description", "Simulator '" + sim.name() + "' (status saat diekspor: " + sim.status() + ")");
        return List.of(server);
    }

    private Map<String, Object> paths(ProductRuntime runtime, UUID simulatorId) {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (EndpointRegistry.EndpointConfig ep : runtime.endpoints().list(simulatorId)) {
            // Satu path bisa punya >1 method (mis. path sama dengan GET & POST): pakai
            // computeIfAbsent agar operasi kedua tak menimpa yang pertama.
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(
                    ep.path(), k -> new LinkedHashMap<String, Object>());
            item.put(ep.method().toLowerCase(), operation(runtime, simulatorId, ep));
        }
        return paths;
    }

    private Map<String, Object> operation(ProductRuntime runtime, UUID simulatorId,
                                          EndpointRegistry.EndpointConfig ep) {
        ProductCatalog catalog = runtime.catalog();
        boolean isCatalogOp = catalog.byKey(ep.operation()).isPresent();

        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", ep.operation());
        op.put("summary", ep.label());
        op.put("tags", List.of(catalog.label()));
        op.put("parameters", parameters(catalog, ep));

        requestBody(catalog, ep).ifPresent(b -> op.put("requestBody", b));

        // Dua daftar yang sengaja dibedakan:
        //  - contoh response boleh memakai nama sintetis "Default" (operasi berlogika tetap),
        //  - x-behavio.scenarios HANYA berisi scenario yang benar-benar ada, karena itulah
        //    yang bisa dipulihkan saat import. Menaruh "Default" di situ akan membuat import
        //    mencoba menyimpan scenario yang tak pernah ada, lalu melapor gagal tanpa sebab.
        List<String> real = safeScenarioNames(runtime.scenarios(), simulatorId, ep.operation());
        List<String> forResponses = real.isEmpty() ? List.of(DEFAULT_SCENARIO) : real;

        Map<String, Object> definitions =
                definitions(runtime.scenarios(), simulatorId, ep.operation(), forResponses);

        op.put("responses", responses(definitions, catalog.requestExample(ep.operation()).orElse(Map.of())));
        op.put(X_BEHAVIO, operationExtension(runtime, simulatorId, ep, isCatalogOp,
                real.isEmpty() ? Map.of() : definitions));
        return op;
    }

    private List<Map<String, Object>> parameters(ProductCatalog catalog, EndpointRegistry.EndpointConfig ep) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (HeaderSpec h : catalog.requestHeaders(ep.operation())) {
            params.add(headerParam(h.name(), h.required(), h.example(), h.description()));
        }
        // Header kustom per-endpoint (kolom endpoints.headers) ditambahkan setelah header
        // standar; nama yang bentrok tidak digandakan.
        customHeaders(ep.headers()).forEach((name, example) -> {
            boolean exists = params.stream().anyMatch(p -> name.equalsIgnoreCase((String) p.get("name")));
            if (!exists) {
                params.add(headerParam(name, false, example, "Header kustom endpoint ini"));
            }
        });
        return params;
    }

    private Map<String, Object> headerParam(String name, boolean required, String example, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", "header");
        p.put("required", required);
        if (description != null && !description.isBlank()) {
            p.put("description", description);
        }
        p.put("schema", Map.of("type", "string"));
        if (example != null && !example.isBlank()) {
            p.put("example", example);
        }
        return p;
    }

    /** {@code endpoints.headers} = objek JSON {nama: contoh}. Isi tak terduga diabaikan. */
    private Map<String, String> customHeaders(String headersJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headersJson == null || headersJson.isBlank()) {
            return out;
        }
        try {
            JsonNode node = mapper.readTree(headersJson);
            if (node.isObject()) {
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception ignored) {
            // kolom bebas-bentuk: gagal parse bukan alasan menggagalkan seluruh export
        }
        return out;
    }

    private Optional<Map<String, Object>> requestBody(ProductCatalog catalog, EndpointRegistry.EndpointConfig ep) {
        Optional<Map<String, Object>> example = catalog.requestExample(ep.operation());
        if (example.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", schemaOf(example.get()));
        media.put("example", example.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", true);
        body.put("content", Map.of("application/json", media));
        return Optional.of(body);
    }

    /**
     * Definisi efektif tiap scenario (JSON {@code ScenarioCodec}) sebagai pohon Jackson.
     * Scenario yang gagal dimuat dilewati, bukan menggagalkan seluruh export — satu
     * scenario rusak tak boleh membuat simulator mustahil diekspor.
     */
    private Map<String, Object> definitions(ScenarioConfigPort scenarios, UUID simulatorId,
                                            String operation, List<String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : names) {
            try {
                out.put(name, mapper.readTree(scenarios.effectiveDefinition(simulatorId, operation, name)));
            } catch (Exception ignored) {
                // scenario tanpa preset/definisi valid — lewati
            }
        }
        return out;
    }

    private List<String> safeScenarioNames(ScenarioConfigPort scenarios, UUID simulatorId, String operation) {
        try {
            return scenarios.scenarioNames(simulatorId, operation);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Tiap scenario → satu contoh response, dikelompokkan pakai {@code httpStatus}-nya.
     * Jadi di Postman "Saldo Kurang" tampil sebagai contoh response {@code 400} —
     * dokumentasi yang selama ini hanya hidup di dashboard (design.md §15.2).
     */
    private Map<String, Object> responses(Map<String, Object> definitions, Map<String, Object> requestExample) {
        Map<String, Object> responses = new LinkedHashMap<>();
        definitions.forEach((name, def) -> {
            JsonNode fallback = ((JsonNode) def).path("fallback");
            JsonNode response = fallback.path("response");
            if (response.isMissingNode()) {
                return;
            }
            String status = String.valueOf(response.path("httpStatus").asInt(200));
            Object body = renderBody(response, requestExample);

            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) responses.computeIfAbsent(status, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("description", "");
                r.put("content", new LinkedHashMap<String, Object>(Map.of(
                        "application/json", new LinkedHashMap<String, Object>(Map.of(
                                "examples", new LinkedHashMap<String, Object>())))));
                return r;
            });
            // Beberapa scenario bisa berbagi status (mis. dua-duanya 200) — kumpulkan
            // namanya di description dan simpan tiap body sebagai example bernama.
            String desc = (String) entry.get("description");
            entry.put("description", desc.isEmpty() ? name : desc + " · " + name);

            Map<String, Object> example = new LinkedHashMap<>();
            example.put("summary", "Scenario: " + name);
            example.put("value", body);
            examplesOf(entry).put(name, example);
        });
        if (responses.isEmpty()) {
            responses.put("200", Map.of("description", "Successful"));
        }
        return responses;
    }

    /**
     * Render {@code {{var}}} di body template jadi nilai konkret.
     *
     * Tanpa ini contoh response di Postman berisi {@code "{{responseCode}}"} alih-alih
     * {@code "2001700"} — bentuknya benar tapi tak menunjukkan apa pun. Dipakai
     * {@link ResponseRenderer} yang SAMA dengan runtime, jadi contohnya berperilaku
     * seperti respons sungguhan: variabel yang tak diketahui jadi string kosong, persis
     * invarian SNAP "field opsional kosong = "" bukan null" (design.md A3.7).
     */
    private Object renderBody(JsonNode response, Map<String, Object> requestExample) {
        JsonNode bodyNode = response.path("body");
        if (bodyNode.isMissingNode() || bodyNode.isNull()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> template = mapper.convertValue(bodyNode, Map.class);
            String json = renderer.render(template, exampleVars(response, requestExample));
            return mapper.convertValue(mapper.readTree(json), Object.class);
        } catch (Exception e) {
            // Template aneh bukan alasan menggagalkan export — kirim apa adanya.
            return mapper.convertValue(bodyNode, Object.class);
        }
    }

    /**
     * Nilai contoh untuk placeholder: diturunkan dari contoh request (agar response
     * konsisten dengan request di sebelahnya) + hasil yang dibuat engine saat runtime.
     */
    private Map<String, Object> exampleVars(JsonNode response, Map<String, Object> requestExample) {
        Map<String, Object> vars = new LinkedHashMap<>();
        flattenExample(requestExample, vars);
        vars.put("responseCode", response.path("responseCode").asText(""));
        vars.put("responseMessage", response.path("responseMessage").asText(""));
        vars.putIfAbsent("referenceNo", "BHV17529000000001234");
        vars.putIfAbsent("transactionDate", "2026-07-15T10:00:00+07:00");
        return vars;
    }

    /**
     * Ratakan contoh request seperti {@code SnapRequestMapper} melakukannya di runtime —
     * termasuk {@code amount:{value,currency}} → {@code amountValue} + {@code currency},
     * karena itulah nama variabel yang dipakai template blueprint.
     */
    @SuppressWarnings("unchecked")
    private void flattenExample(Map<String, Object> example, Map<String, Object> vars) {
        example.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                Object inner = ((Map<String, Object>) nested).get("value");
                if (inner != null) {
                    vars.put(key.equals("amount") ? "amountValue" : key + "Value", inner);
                    Object currency = ((Map<String, Object>) nested).get("currency");
                    if (currency != null) {
                        vars.putIfAbsent("currency", currency);
                    }
                }
                flattenExample((Map<String, Object>) nested, vars);
            } else if (!(value instanceof List<?>)) {
                vars.put(key, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> examplesOf(Map<String, Object> responseEntry) {
        Map<String, Object> content = (Map<String, Object>) responseEntry.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        return (Map<String, Object>) json.get("examples");
    }

    private Map<String, Object> operationExtension(ProductRuntime runtime, UUID simulatorId,
                                                   EndpointRegistry.EndpointConfig ep,
                                                   boolean isCatalogOp, Map<String, Object> definitions) {
        Map<String, Object> x = new LinkedHashMap<>();
        // Kunci katalog, BUKAN path: path justru yang boleh berbeda antar bank (§2), jadi
        // hanya kunci ini yang bisa dipercaya saat import mencocokkan operasi.
        x.put("operation", ep.operation());
        x.put("catalogOperation", isCatalogOp);
        x.put("label", ep.label());
        if (isCatalogOp) {
            x.put("defaultPath", ep.defaultPath());
        }
        safeActiveScenario(runtime.scenarios(), simulatorId, ep.operation())
                .ifPresent(active -> x.put("activeScenario", active));
        x.put("scenarios", definitions);
        return x;
    }

    private Optional<String> safeActiveScenario(ScenarioConfigPort scenarios, UUID simulatorId, String operation) {
        try {
            return scenarios.activeScenarioName(simulatorId, operation);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> rootExtension(SimulatorAdmin.SimulatorView sim, ProductCatalog catalog) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("product", catalog.key());
        x.put("simulatorName", sim.name());
        x.put("port", sim.port());
        x.put("exportedAt", OffsetDateTime.now(ZONE).format(TS));
        x.put("note", "Impor hanya memulihkan endpoint & scenario. Partner/kredensial dan "
                + "state (saldo, transaksi) tidak ikut diekspor.");
        return x;
    }

    /** Schema dangkal dari contoh — cukup untuk Postman; kedalaman penuh ada di `example`. */
    private Map<String, Object> schemaOf(Map<String, Object> example) {
        Map<String, Object> props = new LinkedHashMap<>();
        example.forEach((k, v) -> props.put(k, typeOf(v)));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typeOf(Object v) {
        return switch (v) {
            case null -> Map.of("type", "string", "nullable", true);
            case Map<?, ?> m -> schemaOf((Map<String, Object>) m);
            case List<?> l -> Map.of("type", "array", "items",
                    l.isEmpty() ? Map.of("type", "string") : typeOf(l.get(0)));
            case Boolean b -> Map.of("type", "boolean");
            case Number n -> Map.of("type", "number");
            default -> Map.of("type", "string");
        };
    }
}

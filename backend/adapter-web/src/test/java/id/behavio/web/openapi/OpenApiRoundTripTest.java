package id.behavio.web.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.PartnerAdmin;
import id.behavio.core.port.ScenarioConfigPort;
import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.product.HeaderSpec;
import id.behavio.core.product.Operation;
import id.behavio.core.product.ProductCatalog;
import id.behavio.core.rule.Scenario;
import id.behavio.web.ProductRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Export/import OpenAPI (design.md §15). Memakai katalog & port palsu, bukan bank/QRIS:
 * {@code adapter-web} memang tak boleh melihat modul produk (§2.2 architecture.md), jadi
 * tes ini sekaligus membuktikan exporter/importer benar-benar generik.
 */
class OpenApiRoundTripTest {

    private static final UUID SIM = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String TRANSFER_PATH = "/v1.0/transfer-intrabank";

    /**
     * Sengaja BUKAN "localhost": host harus datang dari pemanggil (PublicHost →
     * DEPLOY_HOST/X-Forwarded-Host). Kalau nilai ini sampai tak muncul di servers[].url,
     * berarti hardcode "localhost" hidup lagi — dan spec yang diimpor ke Postman di mesin
     * lain akan menunjuk mesin itu sendiri.
     */
    private static final String HOST = "behavio.example.test";

    /** Definisi scenario berformat ScenarioCodec, lengkap dengan rule + fault + webhook. */
    private static final String NORMAL_DEF = """
            {
              "rules": [
                {
                  "name": "Saldo tidak cukup",
                  "when": {"kind": "compare",
                           "left": {"kind": "accountBalance", "field": "sourceAccountNo"},
                           "op": "LT",
                           "right": {"kind": "field", "path": "amount"}},
                  "then": {"actions": [],
                           "response": {"httpStatus": 400, "responseCode": "4001714",
                                        "responseMessage": "Insufficient Funds", "body": {}}}
                }
              ],
              "fallback": {
                "actions": [{"kind": "debit", "accountNoField": "sourceAccountNo", "amountField": "amount"}],
                "response": {"httpStatus": 200, "responseCode": "2001700",
                             "responseMessage": "Successful",
                             "body": {"responseCode": "{{responseCode}}", "referenceNo": "{{referenceNo}}"}},
                "fault": {"point": "AFTER_ACTIONS", "delayMillis": 0, "drop": true, "corrupt": false},
                "webhook": {"event": "transfer-notify", "delayMillis": 2000,
                            "bodyTemplate": {"latestTransactionStatus": "00"}}
              }
            }""";

    private static final String INSUFFICIENT_DEF = """
            {
              "rules": [],
              "fallback": {"actions": [],
                           "response": {"httpStatus": 400, "responseCode": "4001714",
                                        "responseMessage": "Insufficient Funds", "body": {}}}
            }""";

    private FakeEndpoints endpoints;
    private FakeScenarios scenarios;
    private ProductRuntime runtime;

    private final OpenApiExporter exporter = new OpenApiExporter();
    private final OpenApiImporter importer = new OpenApiImporter();

    @BeforeEach
    void setUp() {
        endpoints = new FakeEndpoints();
        scenarios = new FakeScenarios();
        endpoints.put("transfer", "POST", TRANSFER_PATH, TRANSFER_PATH, "Transfer Intrabank", null);
        endpoints.put("access-token", "POST", "/v1.0/access-token/b2b", "/v1.0/access-token/b2b",
                "Access Token B2B", null);
        scenarios.put("transfer", "Normal", NORMAL_DEF);
        scenarios.put("transfer", "Saldo Kurang", INSUFFICIENT_DEF);
        scenarios.active.put("transfer", "Normal");
        runtime = runtime("Bank Simulasi Demo", 9001);
    }

    private ProductRuntime runtime(String name, int port) {
        return new ProductRuntime(new FakeCatalog(), new FakeAdmin(name, port, scenarios),
                scenarios, endpoints, null, null, null, Map.of());
    }

    // ---------------- export ----------------

    @Test
    void exportMenghasilkanSpecOpenApiYangBisaDibacaToolLain() {
        Map<String, Object> doc = exporter.export(runtime, SIM, HOST);

        assertThat(doc.get("openapi")).isEqualTo("3.0.3");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) doc.get("servers");
        assertThat(servers.get(0).get("url")).isEqualTo("http://" + HOST + ":9001");

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys(TRANSFER_PATH, "/v1.0/access-token/b2b");

        Map<String, Object> post = operation(doc, TRANSFER_PATH, "post");
        assertThat(post.get("operationId")).isEqualTo("transfer");
        assertThat(post.get("summary")).isEqualTo("Transfer Intrabank");
    }

    @Test
    void requestBodyMempertahankanBentukBersarangSnap() {
        Map<String, Object> post = operation(exporter.export(runtime, SIM, HOST), TRANSFER_PATH, "post");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) post.get("requestBody");
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) body.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) content.get("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> example = (Map<String, Object>) media.get("example");

        // Inti design.md §15.5: amount HARUS objek {value,currency}, bukan skalar rata.
        assertThat(example.get("amount")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) example.get("amount");
        assertThat(amount).containsEntry("value", "15000.00").containsEntry("currency", "IDR");
    }

    @Test
    void headerDideklarasikanKatalogPerOperasi() {
        Map<String, Object> doc = exporter.export(runtime, SIM, HOST);

        assertThat(headerNames(operation(doc, TRANSFER_PATH, "post")))
                .contains("Authorization", "X-PARTNER-ID", "X-EXTERNAL-ID");
        // access-token pakai pola RSA — bukan Bearer (Lampiran A.1)
        assertThat(headerNames(operation(doc, "/v1.0/access-token/b2b", "post")))
                .contains("X-CLIENT-KEY")
                .doesNotContain("Authorization", "X-PARTNER-ID");
    }

    /**
     * Contoh response harus berisi nilai jadi, bukan {@code {{responseCode}}} mentah —
     * template mentah menunjukkan bentuk tapi tak mengajarkan apa pun di Postman.
     */
    @Test
    void contohResponseDirenderJadiNilaiKonkretBukanTemplateMentah() {
        Map<String, Object> post = operation(exporter.export(runtime, SIM, HOST), TRANSFER_PATH, "post");

        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        @SuppressWarnings("unchecked")
        Map<String, Object> r200 = (Map<String, Object>) responses.get("200");

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) exampleValue(r200, "Normal");
        assertThat(value.get("responseCode")).isEqualTo("2001700");
        assertThat((String) value.get("referenceNo")).doesNotContain("{{");
    }

    @Test
    void tiapScenarioJadiContohResponsePadaStatusnyaSendiri() {
        Map<String, Object> post = operation(exporter.export(runtime, SIM, HOST), TRANSFER_PATH, "post");

        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        assertThat(responses).containsKeys("200", "400");

        @SuppressWarnings("unchecked")
        Map<String, Object> r400 = (Map<String, Object>) responses.get("400");
        assertThat((String) r400.get("description")).contains("Saldo Kurang");
    }

    /**
     * design.md §15.3 — file ini dibuat untuk dibagikan, jadi kredensial tak boleh ikut.
     *
     * Sengaja TIDAK melarang kata "saldo"/"balance": "Saldo Kurang" itu nama scenario dan
     * {@code accountBalance} operand di dalam rule — keduanya PERILAKU yang memang harus
     * diekspor. Yang dilarang adalah nilai kredensial & state, bukan kosakatanya.
     */
    @Test
    void kredensialTidakIkutDiekspor() throws Exception {
        Map<String, Object> doc = exporter.export(runtime, SIM, HOST);
        String spec = YAML.writeValueAsString(doc);

        assertThat(spec).doesNotContain("clientSecret", "client_secret", "publicKey", "public_key");

        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) doc.get(OpenApiExporter.X_BEHAVIO);
        assertThat(x).doesNotContainKeys("partners", "accounts", "transactions");
    }

    /**
     * Operasi berlogika tetap (QRIS query/refund/cancel/…) tak punya baris scenario, tapi
     * punya blueprint. Tanpa penanganan khusus mereka diekspor tanpa contoh response —
     * padahal itu yang paling dicari di Postman.
     */
    @Test
    void operasiTanpaScenarioTetapDapatContohResponseDariBlueprint() {
        endpoints.put("qris-query", "POST", "/v1.0/qr/qr-mpm-query", "/v1.0/qr/qr-mpm-query",
                "QRIS — Query", null);
        // Tak ada scenarios.put(...) → meniru Operation.plain: nol baris scenario.
        scenarios.blueprintFallback = """
                {"fallback": {"actions": [],
                 "response": {"httpStatus": 200, "responseCode": "2005100",
                              "responseMessage": "Successful", "body": {"responseCode": "2005100"}}}}""";

        Map<String, Object> post = operation(exporter.export(runtime, SIM, HOST), "/v1.0/qr/qr-mpm-query", "post");

        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        assertThat(responses).containsKey("200");

        // Tapi TIDAK diklaim sebagai scenario yang bisa dipulihkan — tak ada yang restorable.
        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) post.get(OpenApiExporter.X_BEHAVIO);
        @SuppressWarnings("unchecked")
        Map<String, Object> xScenarios = (Map<String, Object>) x.get("scenarios");
        assertThat(xScenarios).isEmpty();
    }

    // ---------------- round-trip ----------------

    @Test
    void roundTripMemulihkanRuleFaultDanWebhookUtuh() throws Exception {
        String spec = YAML.writeValueAsString(exporter.export(runtime, SIM, HOST));

        // Simulator tujuan: kosong, path masih default, tanpa definisi custom apa pun.
        FakeEndpoints targetEndpoints = new FakeEndpoints();
        FakeScenarios targetScenarios = new FakeScenarios();
        targetEndpoints.put("transfer", "POST", TRANSFER_PATH, TRANSFER_PATH, "Transfer Intrabank", null);
        targetScenarios.put("transfer", "Normal", "{}");
        targetScenarios.put("transfer", "Saldo Kurang", "{}");
        ProductRuntime target = new ProductRuntime(new FakeCatalog(),
                new FakeAdmin("Target", 9002, targetScenarios), targetScenarios, targetEndpoints,
                null, null, null, Map.of());

        var preview = importer.preview(target, spec);
        var mappings = preview.rows().stream()
                .filter(r -> "transfer".equals(r.suggestedOperation()))
                .map(r -> new OpenApiImporter.Mapping(r.path(), r.method(),
                        OpenApiImporter.Action.CATALOG, r.suggestedOperation()))
                .toList();

        var result = importer.apply(target, SIM, spec, mappings);

        assertThat(result.scenariosRestored()).isEqualTo(2);
        assertThat(result.messages()).isEmpty();

        // Yang penting bukan "ada isinya", tapi AST-nya identik dengan sumber.
        assertThat(JSON.readTree(targetScenarios.effectiveDefinition(SIM, "transfer", "Normal")))
                .isEqualTo(JSON.readTree(NORMAL_DEF));
        assertThat(JSON.readTree(targetScenarios.effectiveDefinition(SIM, "transfer", "Saldo Kurang")))
                .isEqualTo(JSON.readTree(INSUFFICIENT_DEF));
        assertThat(targetScenarios.active.get("transfer")).isEqualTo("Normal");
    }

    @Test
    void importOperasiKatalogMengOverridePathDariSpecBank() throws Exception {
        // Dokumen "bank lain": path BRI, tapi operasi yang sama.
        String briPath = "/intrabank/snap/v2.0/transfer-intrabank";
        String spec = YAML.writeValueAsString(exporter.export(runtime, SIM, HOST))
                .replace(TRANSFER_PATH, briPath);

        var result = importer.apply(runtime, SIM, spec,
                List.of(new OpenApiImporter.Mapping(briPath, "POST", OpenApiImporter.Action.CATALOG, "transfer")));

        assertThat(result.overridden()).isEqualTo(1);
        assertThat(endpoints.byOperation.get("transfer").path()).isEqualTo(briPath);
    }

    // ---------------- pratinjau ----------------

    @Test
    void pratinjauTidakMengubahApaPun() throws Exception {
        String spec = YAML.writeValueAsString(exporter.export(runtime, SIM, HOST));
        String pathSebelum = endpoints.byOperation.get("transfer").path();
        int tulisSebelum = scenarios.writes;

        var preview = importer.preview(runtime, spec);

        assertThat(preview.rows()).isNotEmpty();
        assertThat(endpoints.byOperation.get("transfer").path()).isEqualTo(pathSebelum);
        assertThat(scenarios.writes).isEqualTo(tulisSebelum);
        assertThat(endpoints.added).isZero();
    }

    @Test
    void tebakanXBehavioLebihDipercayaDaripadaTebakanPath() throws Exception {
        String spec = YAML.writeValueAsString(exporter.export(runtime, SIM, HOST));

        var row = rowOf(importer.preview(runtime, spec), TRANSFER_PATH);
        assertThat(row.suggestedOperation()).isEqualTo("transfer");
        assertThat(row.confidence()).isEqualTo("pasti (x-behavio)");
        assertThat(row.hasBehavior()).isTrue();
        assertThat(row.scenarioNames()).containsExactlyInAnyOrder("Normal", "Saldo Kurang");
    }

    @Test
    void specAsingDitebakDariSuffixPathDanKeyakinannyaDitandai() {
        String spec = """
                openapi: 3.0.3
                info: {title: BRI SNAP, version: "1.0"}
                paths:
                  /intrabank/snap/v2.0/transfer-intrabank:
                    post:
                      summary: Transfer
                  /v2.0/reversal:
                    post:
                      summary: Reversal
                """;
        var preview = importer.preview(runtime, spec);

        var transfer = rowOf(preview, "/intrabank/snap/v2.0/transfer-intrabank");
        assertThat(transfer.suggestedOperation()).isEqualTo("transfer");
        assertThat(transfer.confidence()).isEqualTo("tebakan dari path");
        assertThat(transfer.hasBehavior()).isFalse();

        // Tak dikenali = TIDAK ditebak. Diam-diam menebak berarti meng-override path
        // operasi lain tanpa peringatan (design.md §15.4).
        var reversal = rowOf(preview, "/v2.0/reversal");
        assertThat(reversal.suggestedOperation()).isEmpty();
        assertThat(reversal.confidence()).isEqualTo("tak ada tebakan");
    }

    @Test
    void specRusakDitolakDenganPesanJelas() {
        assertThatThrownBy(() -> importer.preview(runtime, "{ ini bukan spec"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.preview(runtime, "openapi: 3.0.3\ninfo: {title: X}\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths");
    }

    @Test
    void barisYangGagalTidakMembatalkanBarisLain() throws Exception {
        String spec = YAML.writeValueAsString(exporter.export(runtime, SIM, HOST));

        var result = importer.apply(runtime, SIM, spec, List.of(
                new OpenApiImporter.Mapping(TRANSFER_PATH, "POST", OpenApiImporter.Action.CATALOG, "tidak-ada"),
                new OpenApiImporter.Mapping("/v1.0/access-token/b2b", "POST",
                        OpenApiImporter.Action.CATALOG, "access-token")));

        assertThat(result.overridden()).isEqualTo(1);   // access-token tetap jalan
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.messages()).anyMatch(m -> m.contains("tidak-ada"));
    }

    // ---------------- helpers ----------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> operation(Map<String, Object> doc, String path, String method) {
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> item = (Map<String, Object>) paths.get(path);
        return (Map<String, Object>) item.get(method);
    }

    @SuppressWarnings("unchecked")
    private List<String> headerNames(Map<String, Object> operation) {
        List<Map<String, Object>> params = (List<Map<String, Object>>) operation.get("parameters");
        return params.stream().map(p -> (String) p.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private Object exampleValue(Map<String, Object> responseEntry, String scenarioName) {
        Map<String, Object> content = (Map<String, Object>) responseEntry.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        Map<String, Object> examples = (Map<String, Object>) json.get("examples");
        Map<String, Object> example = (Map<String, Object>) examples.get(scenarioName);
        return example.get("value");
    }

    private OpenApiImporter.PreviewRow rowOf(OpenApiImporter.Preview preview, String path) {
        return preview.rows().stream().filter(r -> r.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("Baris pratinjau tak ada untuk " + path));
    }

    // ---------------- fake ----------------

    private static class FakeCatalog implements ProductCatalog {
        @Override public String key() { return "bank"; }
        @Override public String label() { return "Bank"; }

        @Override public List<Operation> operations() {
            return List.of(
                    new Operation("transfer", "POST", TRANSFER_PATH, "Transfer Intrabank",
                            List.of("Normal", "Saldo Kurang")),
                    Operation.plain("access-token", "POST", "/v1.0/access-token/b2b", "Access Token B2B"));
        }

        @Override public Optional<Scenario> blueprint(String operationKey, String scenarioName) {
            return Optional.empty();
        }

        @Override public List<HeaderSpec> requestHeaders(String operationKey) {
            return "access-token".equals(operationKey)
                    ? HeaderSpec.snapAccessToken()
                    : HeaderSpec.snapTransactional();
        }

        @Override public Optional<Map<String, Object>> requestExample(String operationKey) {
            if (!"transfer".equals(operationKey)) return Optional.empty();
            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put("value", "15000.00");
            amount.put("currency", "IDR");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("partnerReferenceNo", "2026071500000000000001");
            body.put("amount", amount);
            body.put("sourceAccountNo", "1234567890");
            return Optional.of(body);
        }
    }

    private static class FakeEndpoints implements EndpointRegistry {
        final Map<String, EndpointConfig> byOperation = new LinkedHashMap<>();
        int added;

        void put(String op, String method, String path, String defaultPath, String label, String headers) {
            byOperation.put(op, new EndpointConfig(op, method, path, defaultPath, label, headers));
        }

        @Override public Optional<String> resolveOperation(UUID s, String m, String p) { return Optional.empty(); }
        @Override public List<EndpointConfig> list(UUID s) { return new ArrayList<>(byOperation.values()); }

        @Override public Optional<EndpointDetail> getDetail(UUID s, String operation) {
            EndpointConfig c = byOperation.get(operation);
            return c == null ? Optional.empty()
                    : Optional.of(new EndpointDetail(c.operation(), c.method(), c.path(),
                            c.defaultPath(), c.label(), c.headers(), true));
        }

        @Override public void updatePath(UUID s, String operation, String newPath) {
            EndpointConfig c = byOperation.get(operation);
            if (c == null) throw new IllegalArgumentException("Endpoint tidak ditemukan: " + operation);
            byOperation.put(operation, new EndpointConfig(c.operation(), c.method(), newPath,
                    c.defaultPath(), c.label(), c.headers()));
        }

        @Override public void resetPath(UUID s, String operation) { }

        @Override public EndpointDetail addEndpoint(UUID s, String method, String path, String headers, String label) {
            added++;
            String op = "custom-" + added;
            put(op, method, path, path, label, headers);
            return new EndpointDetail(op, method, path, path, label, headers, false);
        }

        @Override public void deleteEndpoint(UUID s, String operation) { byOperation.remove(operation); }
        @Override public void updateEndpointMeta(UUID s, String o, String m, String h, String l) { }
    }

    private static class FakeScenarios implements ScenarioConfigPort {
        final Map<String, String> definitions = new LinkedHashMap<>();
        final Map<String, List<String>> names = new LinkedHashMap<>();
        final Map<String, String> active = new LinkedHashMap<>();
        int writes;
        /** Meniru SchemaScenarioConfig: tanpa definisi custom, jatuh ke blueprint. */
        String blueprintFallback;

        void put(String operation, String scenario, String json) {
            definitions.put(operation + "/" + scenario, json);
            names.computeIfAbsent(operation, k -> new ArrayList<>()).add(scenario);
        }

        @Override public List<String> scenarioNames(UUID s, String operation) {
            return names.getOrDefault(operation, List.of());
        }

        @Override public Optional<String> activeScenarioName(UUID s, String operation) {
            return Optional.ofNullable(active.get(operation));
        }

        @Override public String effectiveDefinition(UUID s, String operation, String scenario) {
            String def = definitions.get(operation + "/" + scenario);
            if (def != null) return def;
            if (blueprintFallback != null) return blueprintFallback;
            throw new IllegalArgumentException("Tak ada definisi: " + operation + "/" + scenario);
        }

        @Override public void saveDefinition(UUID s, String operation, String scenario, String json) {
            if (!definitions.containsKey(operation + "/" + scenario)) {
                throw new IllegalArgumentException("Scenario tidak ditemukan: " + scenario);
            }
            writes++;
            definitions.put(operation + "/" + scenario, json);
        }

        @Override public void resetDefinition(UUID s, String operation, String scenario) { }
    }

    private record FakeAdmin(String name, int port, FakeScenarios scenarios) implements SimulatorAdmin {
        @Override public List<SimulatorView> list() { return List.of(); }

        @Override public Optional<SimulatorView> find(UUID id) {
            return Optional.of(new SimulatorView(id, name, port, "STOPPED"));
        }

        @Override public void setStatus(UUID id, String status) { }

        @Override public void setActiveScenario(UUID id, String operation, String scenarioName) {
            scenarios.active.put(operation, scenarioName);
        }

        @Override public UUID create(String n, int p, String mode) { return UUID.randomUUID(); }
        @Override public UUID cloneSimulator(UUID src, String n, int p) { return UUID.randomUUID(); }
        @Override public void delete(UUID id) { }
    }
}

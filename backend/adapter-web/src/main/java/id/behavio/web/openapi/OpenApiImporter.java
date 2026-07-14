package id.behavio.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.product.Operation;
import id.behavio.core.product.ProductCatalog;
import id.behavio.web.ProductRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dokumen OpenAPI → endpoint & scenario simulator (design.md §15.4).
 *
 * Dua langkah yang sengaja dipisah: {@link #preview} <b>tidak menyentuh DB sama
 * sekali</b>, {@link #apply} baru menulis. Alasannya di design.md §15.4: path bank
 * berbeda-beda ({@code /intrabank/snap/v2.0/transfer-intrabank} vs preset
 * {@code /v1.0/transfer-intrabank}), jadi mustahil <i>memastikan</i> sebuah path itu
 * operasi mana. Sistem hanya menyarankan; user yang memutuskan. Menebak diam-diam
 * berarti path operasi lain ter-override tanpa satu pun peringatan.
 */
public class OpenApiImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Apa yang harus dilakukan terhadap satu path di spec. */
    public enum Action {
        /** Petakan ke operasi katalog → path operasi itu di-override. */
        CATALOG,
        /** Buat sebagai endpoint kustom baru. */
        CUSTOM,
        /** Abaikan. */
        SKIP
    }

    /**
     * Satu baris pratinjau.
     *
     * @param suggestedOperation kunci katalog yang disarankan; kosong = tak ada tebakan
     * @param confidence dari mana tebakan datang — ditampilkan agar user tahu seberapa
     *                   layak dipercaya, bukan sekadar disodori dropdown terisi
     */
    public record PreviewRow(String path, String method, String label,
                             String suggestedOperation, String confidence,
                             boolean hasBehavior, List<String> scenarioNames) {}

    public record Preview(String product, String sourceTitle, List<PreviewRow> rows) {}

    /** Keputusan user untuk satu baris. */
    public record Mapping(String path, String method, Action action, String operation) {}

    public record ImportResult(int overridden, int created, int skipped, int scenariosRestored,
                               List<String> messages) {}

    // ---------------- parse ----------------

    /** Terima YAML maupun JSON — Postman & bank mengeluarkan keduanya. */
    public JsonNode parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Spec kosong");
        }
        String trimmed = spec.trim();
        try {
            return trimmed.startsWith("{") ? JSON.readTree(trimmed) : YAML.readTree(trimmed);
        } catch (Exception e) {
            // JSON juga YAML valid, tapi tidak sebaliknya — coba sekali lagi lewat YAML.
            try {
                return YAML.readTree(trimmed);
            } catch (Exception e2) {
                throw new IllegalArgumentException("Spec bukan OpenAPI YAML/JSON yang valid: " + e2.getMessage());
            }
        }
    }

    // ---------------- pratinjau (read-only) ----------------

    public Preview preview(ProductRuntime runtime, String spec) {
        JsonNode root = parse(spec);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            throw new IllegalArgumentException("Spec tak punya bagian 'paths'");
        }
        List<PreviewRow> rows = new ArrayList<>();
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                String method = methodEntry.getKey();
                if (!isHttpMethod(method)) {
                    return; // 'parameters', 'summary', dll. di level path-item
                }
                rows.add(row(runtime.catalog(), path, method, methodEntry.getValue()));
            });
        });
        return new Preview(runtime.key(), root.path("info").path("title").asText(""), rows);
    }

    private PreviewRow row(ProductCatalog catalog, String path, String method, JsonNode op) {
        JsonNode x = op.path(OpenApiExporter.X_BEHAVIO);
        List<String> scenarios = new ArrayList<>();
        x.path("scenarios").fieldNames().forEachRemaining(scenarios::add);

        Suggestion s = suggest(catalog, path, method, op, x);
        String label = firstNonBlank(
                x.path("label").asText(""),
                op.path("summary").asText(""),
                path);
        return new PreviewRow(path, method.toUpperCase(Locale.ROOT), label,
                s.operation, s.confidence, !scenarios.isEmpty(), scenarios);
    }

    private record Suggestion(String operation, String confidence) {}

    /**
     * Tebakan dengan keyakinan menurun (design.md §15.4). Urutannya penting: hanya
     * {@code x-behavio.operation} yang pasti — sisanya benar-benar tebakan, dan
     * label keyakinannya ikut ditampilkan ke user.
     */
    private Suggestion suggest(ProductCatalog catalog, String path, String method, JsonNode op, JsonNode x) {
        String fromExt = x.path("operation").asText("");
        if (!fromExt.isBlank() && catalog.byKey(fromExt).isPresent()) {
            return new Suggestion(fromExt, "pasti (x-behavio)");
        }
        String opId = op.path("operationId").asText("");
        if (!opId.isBlank() && catalog.byKey(opId).isPresent()) {
            return new Suggestion(catalog.byKey(opId).get().key(), "operationId cocok");
        }
        Optional<Operation> bySuffix = matchBySuffix(catalog, path, method);
        if (bySuffix.isPresent()) {
            return new Suggestion(bySuffix.get().key(), "tebakan dari path");
        }
        return new Suggestion("", "tak ada tebakan");
    }

    /**
     * Cocokkan segmen terakhir path ({@code …/transfer-intrabank} ↔ preset
     * {@code /v1.0/transfer-intrabank}). Method harus sama — path yang mirip dengan
     * method beda hampir pasti operasi lain.
     */
    private Optional<Operation> matchBySuffix(ProductCatalog catalog, String path, String method) {
        String suffix = lastSegment(path);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        return catalog.operations().stream()
                .filter(o -> o.method().equalsIgnoreCase(method))
                .filter(o -> lastSegment(o.defaultPath()).equalsIgnoreCase(suffix))
                .findFirst();
    }

    private static String lastSegment(String path) {
        String p = path == null ? "" : path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    // ---------------- terapkan ----------------

    /**
     * Terapkan keputusan user. Spec dibaca ulang di sini (bukan mengandalkan pratinjau
     * yang dikirim balik) agar perilaku yang ditulis benar-benar berasal dari file,
     * bukan dari payload yang bisa saja sudah berubah di perjalanan.
     */
    public ImportResult apply(ProductRuntime runtime, UUID simulatorId, String spec, List<Mapping> mappings) {
        JsonNode root = parse(spec);
        JsonNode paths = root.path("paths");
        List<String> messages = new ArrayList<>();
        int overridden = 0;
        int created = 0;
        int skipped = 0;
        int restored = 0;

        for (Mapping m : mappings) {
            if (m.action() == Action.SKIP) {
                skipped++;
                continue;
            }
            JsonNode op = paths.path(m.path()).path(m.method().toLowerCase(Locale.ROOT));
            if (op.isMissingNode()) {
                messages.add("Dilewati — tak ada di spec: " + m.method() + " " + m.path());
                skipped++;
                continue;
            }
            try {
                String targetOperation = switch (m.action()) {
                    case CATALOG -> applyCatalog(runtime, simulatorId, m);
                    case CUSTOM -> applyCustom(runtime, simulatorId, m, op);
                    case SKIP -> null;
                };
                if (targetOperation == null) {
                    skipped++;
                    continue;
                }
                if (m.action() == Action.CATALOG) overridden++; else created++;
                restored += restoreBehavior(runtime, simulatorId, targetOperation, op, messages);
            } catch (IllegalArgumentException e) {
                // Satu baris gagal tak boleh membatalkan sisanya — laporkan, lanjutkan.
                messages.add("Gagal " + m.method() + " " + m.path() + ": " + e.getMessage());
                skipped++;
            }
        }
        return new ImportResult(overridden, created, skipped, restored, messages);
    }

    private String applyCatalog(ProductRuntime runtime, UUID simulatorId, Mapping m) {
        String key = m.operation();
        Operation op = runtime.catalog().byKey(key).orElseThrow(() ->
                new IllegalArgumentException("Bukan operasi katalog " + runtime.key() + ": '" + key + "'"));
        runtime.endpoints().updatePath(simulatorId, op.key(), m.path());
        return op.key();
    }

    private String applyCustom(ProductRuntime runtime, UUID simulatorId, Mapping m, JsonNode op) {
        String headers = headersJson(op);
        String label = firstNonBlank(op.path("summary").asText(""), "Impor " + m.path());
        EndpointRegistry.EndpointDetail created =
                runtime.endpoints().addEndpoint(simulatorId, m.method(), m.path(), headers, label);
        return created.operation();
    }

    /** Header non-standar dari spec disimpan ke kolom {@code endpoints.headers}. */
    private String headersJson(JsonNode op) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode p : op.path("parameters")) {
            if (!"header".equalsIgnoreCase(p.path("in").asText(""))) continue;
            String name = p.path("name").asText("");
            if (name.isBlank()) continue;
            out.put(name, p.path("example").asText(""));
        }
        try {
            return out.isEmpty() ? null : JSON.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pulihkan scenario dari {@code x-behavio}. Definisi dilewatkan apa adanya ke
     * {@link id.behavio.core.port.ScenarioConfigPort#saveDefinition}, yang sudah
     * memvalidasinya lewat {@code ScenarioCodec.parse} — importer ini tak perlu (dan tak
     * boleh) mengenal AST rule.
     */
    private int restoreBehavior(ProductRuntime runtime, UUID simulatorId, String operation,
                                JsonNode op, List<String> messages) {
        JsonNode scenarios = op.path(OpenApiExporter.X_BEHAVIO).path("scenarios");
        if (!scenarios.isObject()) {
            return 0;
        }
        int restored = 0;
        List<String> names = new ArrayList<>();
        scenarios.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            try {
                runtime.scenarios().saveDefinition(simulatorId, operation, name,
                        JSON.writeValueAsString(scenarios.get(name)));
                restored++;
            } catch (Exception e) {
                messages.add("Scenario '" + name + "' pada " + operation + " tak dipulihkan: " + e.getMessage());
            }
        }
        String active = op.path(OpenApiExporter.X_BEHAVIO).path("activeScenario").asText("");
        if (!active.isBlank()) {
            try {
                runtime.admin().setActiveScenario(simulatorId, operation, active);
            } catch (Exception e) {
                messages.add("Scenario aktif '" + active + "' pada " + operation + " tak diterapkan: " + e.getMessage());
            }
        }
        return restored;
    }

    private static boolean isHttpMethod(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "get", "post", "put", "patch", "delete", "head", "options", "trace" -> true;
            default -> false;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}

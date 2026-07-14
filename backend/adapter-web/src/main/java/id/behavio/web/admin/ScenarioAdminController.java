package id.behavio.web.admin;

import id.behavio.core.port.ScenarioConfigPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: edit definisi scenario (kondisi request + response) dari dashboard.
 * Definisi berupa JSON (mirror AST engine); dapat di-override & di-reset ke preset.
 * {@code product} (default "transfer") memilih endpoint mana yang diedit — generik
 * lintas endpoint (mis. "qris").
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/scenarios")
public class ScenarioAdminController {

    private final ScenarioConfigPort scenarios;

    public ScenarioAdminController(ScenarioConfigPort scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<String> list(@PathVariable UUID id, @RequestParam(defaultValue = "transfer") String product) {
        List<String> names = scenarios.scenarioNames(id, product);
        return names.isEmpty() ? List.of("Normal") : names;
    }

    /** Scenario yang SEDANG aktif — dipakai dashboard agar dropdown sinkron dengan server. */
    @GetMapping("/active")
    public ResponseEntity<?> active(@PathVariable UUID id, @RequestParam(defaultValue = "transfer") String product) {
        String name = scenarios.activeScenarioName(id, product).orElse("Normal");
        return ResponseEntity.ok(Map.of("name", name));
    }

    @GetMapping(value = "/{name}/definition", produces = MediaType.TEXT_PLAIN_VALUE)
    public String get(@PathVariable UUID id, @PathVariable String name,
                      @RequestParam(defaultValue = "transfer") String product) {
        try {
            return scenarios.effectiveDefinition(id, product, name);
        } catch (Exception e) {
            return scenarios.effectiveDefinition(id, product, "Normal");
        }
    }

    @PutMapping(value = "/{name}/definition", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> save(@PathVariable UUID id, @PathVariable String name,
                                  @RequestParam(defaultValue = "transfer") String product,
                                  @RequestBody String json) {
        scenarios.saveDefinition(id, product, name, json);
        return ResponseEntity.ok(Map.of("status", "saved", "scenario", name));
    }

    @DeleteMapping("/{name}/definition")
    public ResponseEntity<?> reset(@PathVariable UUID id, @PathVariable String name,
                                   @RequestParam(defaultValue = "transfer") String product) {
        scenarios.resetDefinition(id, product, name);
        return ResponseEntity.ok(Map.of("status", "reset", "scenario", name));
    }
}

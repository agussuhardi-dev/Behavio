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
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/scenarios")
public class ScenarioAdminController {

    private final ScenarioConfigPort scenarios;

    public ScenarioAdminController(ScenarioConfigPort scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<String> list(@PathVariable UUID id) {
        return scenarios.scenarioNames(id);
    }

    @GetMapping(value = "/{name}/definition", produces = MediaType.TEXT_PLAIN_VALUE)
    public String get(@PathVariable UUID id, @PathVariable String name) {
        return scenarios.effectiveDefinition(id, name);
    }

    @PutMapping(value = "/{name}/definition", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> save(@PathVariable UUID id, @PathVariable String name, @RequestBody String json) {
        try {
            scenarios.saveDefinition(id, name, json);
            return ResponseEntity.ok(Map.of("status", "saved", "scenario", name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}/definition")
    public ResponseEntity<?> reset(@PathVariable UUID id, @PathVariable String name) {
        scenarios.resetDefinition(id, name);
        return ResponseEntity.ok(Map.of("status", "reset", "scenario", name));
    }
}

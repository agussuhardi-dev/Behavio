package id.behavio.iso.web;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.persistence.IsoScenarioRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API scenario ISO-8583 — bentuknya sengaja dibuat mencerminkan produk HTTP
 * (daftar / aktif / definisi / reset), hanya kuncinya <b>operasi</b>, bukan method+path.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:iso8583}/simulators/{id}/scenarios")
public class IsoScenarioAdminController {

    private final IsoScenarioRepository scenarios;

    public IsoScenarioAdminController(IsoScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<String> list(@PathVariable UUID id, @RequestParam String operation) {
        return scenarios.names(id, operation);
    }

    @GetMapping("/active")
    public Map<String, String> active(@PathVariable UUID id, @RequestParam String operation) {
        return Map.of("name", scenarios.activeName(id, operation).orElse("Normal"));
    }

    @PutMapping("/active")
    public Map<String, String> setActive(@PathVariable UUID id,
                                         @RequestParam String operation,
                                         @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IsoCodecException("field 'name' wajib");
        }
        scenarios.setActive(id, operation, name);
        return Map.of("operation", operation, "activeScenario", name);
    }

    /** Definisi scenario — inilah yang disunting user ("Edit Response" versi ISO). */
    @GetMapping(value = "/{name}/definition", produces = MediaType.APPLICATION_JSON_VALUE)
    public String definition(@PathVariable UUID id, @PathVariable String name,
                             @RequestParam String operation) {
        return scenarios.definition(id, operation, name).orElseThrow(() ->
                new IsoCodecException("Scenario '" + name + "' tidak ada untuk operasi '" + operation + "'"));
    }

    @PutMapping(value = "/{name}/definition", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> save(@PathVariable UUID id, @PathVariable String name,
                                    @RequestParam String operation, @RequestBody String json) {
        scenarios.saveDefinition(id, operation, name, json);
        return Map.of("status", "saved", "scenario", name);
    }

    @DeleteMapping("/{name}/definition")
    public Map<String, String> reset(@PathVariable UUID id, @PathVariable String name,
                                     @RequestParam String operation) {
        scenarios.resetDefinition(id, operation, name);
        return Map.of("status", "reset", "scenario", name);
    }

    @ExceptionHandler(IsoCodecException.class)
    public ResponseEntity<?> handle(IsoCodecException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

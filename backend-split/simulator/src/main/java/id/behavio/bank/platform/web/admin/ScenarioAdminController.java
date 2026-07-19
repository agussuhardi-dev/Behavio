package id.behavio.bank.platform.web.admin;

import id.behavio.bank.platform.web.ProductRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: edit definisi scenario (kondisi request + response) dari dashboard.
 * Definisi berupa JSON (cermin AST engine); dapat di-override & di-reset ke preset.
 *
 * {@code operation} memilih endpoint mana yang diedit (mis. "transfer", "qris-generate").
 * Nilainya divalidasi terhadap katalog produk, jadi meminta operasi QRIS di bawah
 * {@code /bank/} ditolak rapi alih-alih diam-diam jatuh ke transfer seperti sebelumnya.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:bank}/simulators/{id}/scenarios")
public class ScenarioAdminController {

    private final ProductRegistry products;

    public ScenarioAdminController(ProductRegistry products) {
        this.products = products;
    }

    @GetMapping
    public List<String> list(@PathVariable String product, @PathVariable UUID id,
                             @RequestParam String operation) {
        List<String> names = products.require(product).scenarios().scenarioNames(id, operation);
        return names.isEmpty() ? List.of("Normal") : names;
    }

    /** Scenario yang SEDANG aktif — dipakai dashboard agar dropdown sinkron dengan server. */
    @GetMapping("/active")
    public ResponseEntity<?> active(@PathVariable String product, @PathVariable UUID id,
                                    @RequestParam String operation) {
        String name = products.require(product).scenarios().activeScenarioName(id, operation).orElse("Normal");
        return ResponseEntity.ok(Map.of("name", name));
    }

    @GetMapping(value = "/{name}/definition", produces = MediaType.TEXT_PLAIN_VALUE)
    public String get(@PathVariable String product, @PathVariable UUID id, @PathVariable String name,
                      @RequestParam String operation) {
        return products.require(product).scenarios().effectiveDefinition(id, operation, name);
    }

    @PutMapping(value = "/{name}/definition", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> save(@PathVariable String product, @PathVariable UUID id, @PathVariable String name,
                                  @RequestParam String operation, @RequestBody String json) {
        products.require(product).scenarios().saveDefinition(id, operation, name, json);
        return ResponseEntity.ok(Map.of("status", "saved", "scenario", name));
    }

    @DeleteMapping("/{name}/definition")
    public ResponseEntity<?> reset(@PathVariable String product, @PathVariable UUID id, @PathVariable String name,
                                   @RequestParam String operation) {
        products.require(product).scenarios().resetDefinition(id, operation, name);
        return ResponseEntity.ok(Map.of("status", "reset", "scenario", name));
    }
}

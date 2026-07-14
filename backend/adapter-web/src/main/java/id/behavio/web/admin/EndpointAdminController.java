package id.behavio.web.admin;

import id.behavio.core.port.EndpointRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: full CRUD URL endpoint per-simulator. Mendukung:
 * - List semua endpoint (SNAP + kustom)
 * - Tambah endpoint kustom
 * - Edit path/method/headers
 * - Hapus endpoint
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/endpoints")
public class EndpointAdminController {

    private final EndpointRegistry registry;

    public EndpointAdminController(EndpointRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<EndpointRegistry.EndpointConfig> list(@PathVariable UUID id) {
        return registry.list(id);
    }

    @PostMapping
    public ResponseEntity<?> add(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String method = body.getOrDefault("method", "POST");
        String path = body.get("path");
        String headers = body.get("headers");
        String label = body.get("label");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path wajib diisi"));
        }
        try {
            var ep = registry.addEndpoint(id, method, path, headers, label);
            return ResponseEntity.ok(Map.of("operation", ep.operation(), "method", ep.method(),
                    "path", ep.path(), "label", ep.label()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{operation}")
    public ResponseEntity<?> update(@PathVariable UUID id, @PathVariable String operation,
                                    @RequestBody Map<String, String> body) {
        String newPath = body.get("path");
        String method = body.get("method");
        String headers = body.get("headers");
        if (newPath != null) {
            try {
                registry.updatePath(id, operation, newPath);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }
        if (method != null || headers != null) {
            try {
                registry.updateEndpointMeta(id, operation,
                        method != null ? method : "POST",
                        headers, body.get("label"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("operation", operation));
    }

    @DeleteMapping("/{operation}")
    public ResponseEntity<?> delete(@PathVariable UUID id, @PathVariable String operation) {
        try {
            registry.deleteEndpoint(id, operation);
            return ResponseEntity.ok(Map.of("operation", operation, "status", "deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{operation}")
    public ResponseEntity<?> getDetail(@PathVariable UUID id, @PathVariable String operation) {
        return registry.getDetail(id, operation)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

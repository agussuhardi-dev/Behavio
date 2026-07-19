package id.behavio.qris.platform.web.admin;

import id.behavio.qris.platform.core.port.EndpointRegistry;
import id.behavio.qris.platform.web.ProductRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: full CRUD URL endpoint per-simulator, generik lintas produk. Mendukung:
 * - List semua endpoint (SNAP + kustom)
 * - Tambah endpoint kustom
 * - Edit path/method/headers
 * - Hapus endpoint
 */
@RestController
@RequestMapping("/api/admin/v1/{product:qris}/simulators/{id}/endpoints")
public class EndpointAdminController {

    private final ProductRegistry products;

    public EndpointAdminController(ProductRegistry products) {
        this.products = products;
    }

    /** Registry milik produk di URL — katalog default & validasi operasinya ikut produk itu. */
    private EndpointRegistry registry(String product) {
        return products.require(product).endpoints();
    }

    @GetMapping
    public List<EndpointRegistry.EndpointConfig> list(@PathVariable String product, @PathVariable UUID id) {
        return registry(product).list(id);
    }

    @PostMapping
    public ResponseEntity<?> add(@PathVariable String product, @PathVariable UUID id, @RequestBody Map<String, String> body) {
        String method = body.getOrDefault("method", "POST");
        String path = body.get("path");
        String headers = body.get("headers");
        String label = body.get("label");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path wajib diisi"));
        }
        try {
            var ep = registry(product).addEndpoint(id, method, path, headers, label);
            return ResponseEntity.ok(Map.of("operation", ep.operation(), "method", ep.method(),
                    "path", ep.path(), "label", ep.label()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{operation}")
    public ResponseEntity<?> update(@PathVariable String product, @PathVariable UUID id, @PathVariable String operation,
                                    @RequestBody Map<String, String> body) {
        String newPath = body.get("path");
        String method = body.get("method");
        String headers = body.get("headers");
        if (newPath != null) {
            try {
                registry(product).updatePath(id, operation, newPath);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }
        if (method != null || headers != null) {
            try {
                registry(product).updateEndpointMeta(id, operation,
                        method != null ? method : "POST",
                        headers, body.get("label"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("operation", operation));
    }

    @DeleteMapping("/{operation}")
    public ResponseEntity<?> delete(@PathVariable String product, @PathVariable UUID id, @PathVariable String operation) {
        try {
            registry(product).deleteEndpoint(id, operation);
            return ResponseEntity.ok(Map.of("operation", operation, "status", "deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{operation}")
    public ResponseEntity<?> getDetail(@PathVariable String product, @PathVariable UUID id, @PathVariable String operation) {
        return registry(product).getDetail(id, operation)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

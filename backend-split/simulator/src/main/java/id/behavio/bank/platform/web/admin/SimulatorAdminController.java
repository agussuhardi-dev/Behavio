package id.behavio.bank.platform.web.admin;

import id.behavio.bank.platform.core.port.SimulatorAdmin.SimulatorView;
import id.behavio.bank.platform.web.ProductRegistry;
import id.behavio.bank.platform.web.ProductRuntime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API mengelola simulator: list, create/clone/delete, start/stop port, ganti
 * scenario aktif. Generik lintas produk lewat segmen {@code {product}} —
 * {@code /api/admin/v1/bank/simulators} dan {@code /api/admin/v1/qris/simulators}
 * dilayani kode yang sama, hanya runtime & schema-nya berbeda.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:bank}/simulators")
public class SimulatorAdminController {

    private final ProductRegistry products;

    public SimulatorAdminController(ProductRegistry products) {
        this.products = products;
    }

    @GetMapping
    public List<SimulatorView> list(@PathVariable String product) {
        return products.require(product).admin().list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimulatorView> get(@PathVariable String product, @PathVariable UUID id) {
        return products.require(product).admin().find(id)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Buat profil baru — baseline lengkap (partner, endpoint, scenario katalog produk). */
    @PostMapping
    public ResponseEntity<?> create(@PathVariable String product, @RequestBody Map<String, Object> body) {
        ProductRuntime rt = products.require(product);
        String name = str(body.get("name"));
        Integer port = num(body.get("port"));
        String sigMode = str(body.getOrDefault("signatureMode", "SIMULATED"));
        if (name == null || name.isBlank() || port == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' dan 'port' wajib"));
        }
        // Bentrok port dideteksi DB lewat platform.port_registry (lintas produk) — cek
        // baca-lalu-tulis di sini hanya akan melihat produk ini sendiri.
        try {
            UUID id = rt.admin().create(name, port, sigMode);
            return ResponseEntity.ok(rt.admin().find(id).orElseThrow());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Duplikat profil: konfigurasi + override + state awal disalin, port/nama baru. */
    @PostMapping("/{id}/clone")
    public ResponseEntity<?> clone(@PathVariable String product, @PathVariable UUID id,
                                   @RequestBody Map<String, Object> body) {
        ProductRuntime rt = products.require(product);
        if (rt.admin().find(id).isEmpty()) return ResponseEntity.notFound().build();
        String name = str(body.get("name"));
        Integer port = num(body.get("port"));
        if (name == null || name.isBlank() || port == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' dan 'port' wajib"));
        }
        try {
            UUID newId = rt.admin().cloneSimulator(id, name, port);
            return ResponseEntity.ok(rt.admin().find(newId).orElseThrow());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String product, @PathVariable UUID id) {
        ProductRuntime rt = products.require(product);
        if (rt.admin().find(id).isEmpty()) return ResponseEntity.notFound().build();
        rt.servers().stop(id);   // pastikan port ditutup sebelum config dihapus
        rt.admin().delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String product, @PathVariable UUID id) {
        ProductRuntime rt = products.require(product);
        SimulatorView v = rt.admin().find(id).orElse(null);
        if (v == null) return ResponseEntity.notFound().build();
        rt.servers().start(id, v.port());
        rt.admin().setStatus(id, "RUNNING");
        return ResponseEntity.ok(Map.of("id", id, "status", "RUNNING", "port", v.port()));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable String product, @PathVariable UUID id) {
        ProductRuntime rt = products.require(product);
        if (rt.admin().find(id).isEmpty()) return ResponseEntity.notFound().build();
        rt.servers().stop(id);
        rt.admin().setStatus(id, "STOPPED");
        return ResponseEntity.ok(Map.of("id", id, "status", "STOPPED"));
    }

    @PutMapping("/{id}/active-scenario")
    public ResponseEntity<?> setActiveScenario(@PathVariable String product, @PathVariable UUID id,
                                               @RequestParam(required = false) String operation,
                                               @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' wajib"));
        }
        String op = operation == null ? defaultOperation(product) : operation;
        products.require(product).admin().setActiveScenario(id, op, name);
        return ResponseEntity.ok(Map.of("id", id, "operation", op, "activeScenario", name));
    }

    /** Operasi utama tiap produk — dipakai bila query param `operation` tak diberikan. */
    private String defaultOperation(String product) {
        return "qris".equals(products.require(product).key()) ? "qris-generate" : "transfer";
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static Integer num(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}

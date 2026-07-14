package id.behavio.web.admin;

import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.port.SimulatorAdmin.SimulatorView;
import id.behavio.web.SimulatorServerManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin API untuk mengelola simulator: list, start/stop port, ganti scenario aktif. */
@RestController
@RequestMapping("/api/admin/v1/simulators")
public class SimulatorAdminController {

    private final SimulatorAdmin admin;
    private final SimulatorServerManager ports;

    public SimulatorAdminController(SimulatorAdmin admin, SimulatorServerManager ports) {
        this.admin = admin;
        this.ports = ports;
    }

    @GetMapping
    public List<SimulatorView> list() {
        return admin.list();
    }

    /** Buat simulator (profil bank) baru — baseline SNAP lengkap (partner, akun, 8 scenario). */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        Integer port = num(body.get("port"));
        String sigMode = str(body.getOrDefault("signatureMode", "SIMULATED"));
        if (name == null || name.isBlank() || port == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' dan 'port' wajib"));
        }
        if (portTaken(port)) {
            return ResponseEntity.status(409).body(Map.of("error", "Port " + port + " sudah dipakai simulator lain"));
        }
        UUID id = admin.create(name, port, sigMode);
        return ResponseEntity.ok(admin.find(id).orElseThrow());
    }

    /** Duplikat profil bank: konfigurasi + override + akun disalin, port/nama baru. */
    @PostMapping("/{id}/clone")
    public ResponseEntity<?> clone(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (admin.find(id).isEmpty()) return ResponseEntity.notFound().build();
        String name = str(body.get("name"));
        Integer port = num(body.get("port"));
        if (name == null || name.isBlank() || port == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' dan 'port' wajib"));
        }
        if (portTaken(port)) {
            return ResponseEntity.status(409).body(Map.of("error", "Port " + port + " sudah dipakai simulator lain"));
        }
        UUID newId = admin.cloneSimulator(id, name, port);
        return ResponseEntity.ok(admin.find(newId).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        if (admin.find(id).isEmpty()) return ResponseEntity.notFound().build();
        ports.stop(id); // pastikan port ditutup sebelum config dihapus
        admin.delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    private boolean portTaken(int port) {
        return admin.list().stream().anyMatch(s -> s.port() == port);
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static Integer num(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimulatorView> get(@PathVariable UUID id) {
        return admin.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable UUID id) {
        SimulatorView v = admin.find(id).orElse(null);
        if (v == null) return ResponseEntity.notFound().build();
        ports.start(id, v.port());
        admin.setStatus(id, "RUNNING");
        return ResponseEntity.ok(Map.of("id", id, "status", "RUNNING", "port", v.port()));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable UUID id) {
        if (admin.find(id).isEmpty()) return ResponseEntity.notFound().build();
        ports.stop(id);
        admin.setStatus(id, "STOPPED");
        return ResponseEntity.ok(Map.of("id", id, "status", "STOPPED"));
    }

    @PutMapping("/{id}/active-scenario")
    public ResponseEntity<?> setActiveScenario(@PathVariable UUID id,
                                               @RequestParam(defaultValue = "transfer") String product,
                                               @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'name' wajib"));
        }
        try {
            admin.setActiveScenario(id, product, name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("id", id, "product", product, "activeScenario", name));
    }
}

package id.behavio.iso.web;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.persistence.IsoStateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API simulator ISO-8583. Segmen produk ber-constraint {@code {product:iso8583}}
 * mengikuti pola bank/qris agar pola path tak pernah bertabrakan di main-app.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:iso8583}/simulators")
public class IsoSimulatorAdminController {

    private final IsoSimulatorService service;
    private final IsoStateRepository state;

    public IsoSimulatorAdminController(IsoSimulatorService service, IsoStateRepository state) {
        this.service = service;
        this.state = state;
    }

    @GetMapping
    public List<IsoSimulatorService.SimulatorView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        return service.find(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public IsoSimulatorService.SimulatorView create(@RequestBody Map<String, Object> body) {
        String name = str(body, "name", "ISO Simulator");
        int port = body.get("port") instanceof Number n ? n.intValue() : 9201;
        String profile = str(body, "specProfileName", "iso8583-1987");
        String version = str(body, "specProfileVersion", "1.0");
        return service.create(name, port, profile, version);
    }

    @PostMapping("/{id}/start")
    public IsoSimulatorService.SimulatorView start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PostMapping("/{id}/stop")
    public IsoSimulatorService.SimulatorView stop(@PathVariable UUID id) {
        return service.stop(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        service.delete(id);
        return Map.of("id", id, "status", "deleted");
    }

    // ── state: rekening & kartu ────────────────────────────────────────────

    @GetMapping("/{id}/accounts")
    public List<IsoStateRepository.Account> accounts(@PathVariable UUID id) {
        return state.listAccounts(id);
    }

    @PostMapping("/{id}/accounts")
    public Map<String, Object> addAccount(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        UUID accId = state.addAccount(id, str(b, "accountNo", null), str(b, "holderName", "Nasabah"),
                new BigDecimal(str(b, "balance", "0")), str(b, "currency", "360"));
        return Map.of("id", accId, "status", "created");
    }

    @PostMapping("/{id}/cards")
    public Map<String, Object> addCard(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        UUID cardId = state.addCard(id, str(b, "pan", null), str(b, "accountNo", null));
        return Map.of("id", cardId, "status", "created");
    }

    /** Seed contoh agar simulator langsung bisa diuji tanpa menyiapkan data manual. */
    @PostMapping("/{id}/seed-demo")
    public Map<String, Object> seed(@PathVariable UUID id) {
        return service.seedDemo(id);
    }

    /** Live View sederhana: pesan masuk/keluar dalam HEX, siap ditempel ke uji trace. */
    @GetMapping("/{id}/logs")
    public List<Map<String, Object>> logs(@PathVariable UUID id,
                                          @RequestParam(defaultValue = "20") int limit) {
        return state.recentLogs(id, limit);
    }

    private static String str(Map<String, Object> b, String k, String def) {
        Object v = b.get(k);
        return v == null ? def : String.valueOf(v);
    }

    @ExceptionHandler(IsoCodecException.class)
    public ResponseEntity<?> handle(IsoCodecException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

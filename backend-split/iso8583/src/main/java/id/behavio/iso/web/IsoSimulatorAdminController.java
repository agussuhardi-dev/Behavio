package id.behavio.iso.web;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.persistence.IsoStateRepository;
import id.behavio.iso.spec.ShinhanProfileSeeder;
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
        // Bawaan = profil Shinhan LENGKAP (19 operasi), bukan baseline generik: simulator
        // yang baru dibuat harus langsung bisa melayani semua transaksi, bukan sebagian.
        // Nama & versinya diambil dari seeder-nya, bukan ditulis ulang — string yang
        // dihardcode di sini pernah menunjuk versi yang sudah tak ada lagi, dan pembuatan
        // simulator gagal total karenanya.
        String profile = str(body, "specProfileName", ShinhanProfileSeeder.NAME);
        String version = str(body, "specProfileVersion", ShinhanProfileSeeder.VERSION);
        return service.create(name, port, profile, version);
    }

    /**
     * Mengalihkan simulator ke profil spec lain tanpa membuat ulang simulatornya —
     * rekening, kartu, dan riwayat pesannya tetap utuh.
     */
    @PutMapping("/{id}/spec-profile")
    public IsoSimulatorService.SimulatorView switchProfile(@PathVariable UUID id,
                                                          @RequestBody Map<String, String> b) {
        String name = b.get("specProfileName");
        String version = b.get("specProfileVersion");
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            throw new IsoCodecException("specProfileName & specProfileVersion wajib diisi");
        }
        return service.switchProfile(id, name.trim(), version.trim());
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
        String accountNo = str(b, "accountNo", null);
        UUID accId = state.addAccount(id, accountNo, str(b, "holderName", "Nasabah"),
                new BigDecimal(str(b, "balance", "0")), str(b, "currency", "360"));
        String phone = str(b, "phone", "");
        if (!phone.isBlank()) {
            state.updatePhone(id, accountNo, phone.trim());
        }
        return Map.of("id", accId, "status", "created");
    }

    @GetMapping("/{id}/cards")
    public List<IsoStateRepository.Card> cards(@PathVariable UUID id) {
        return state.listCards(id);
    }

    @DeleteMapping("/{id}/accounts/{accountNo}")
    public Map<String, Object> deleteAccount(@PathVariable UUID id, @PathVariable String accountNo) {
        state.deleteAccount(id, accountNo);
        return Map.of("status", "deleted", "accountNo", accountNo);
    }

    @DeleteMapping("/{id}/cards/{pan}")
    public Map<String, Object> deleteCard(@PathVariable UUID id, @PathVariable String pan) {
        state.deleteCard(id, pan);
        return Map.of("status", "deleted", "pan", pan);
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

    /**
     * Riwayat pesan berhalaman. Pesan BARU datang lewat SSE ({@code /logs/stream});
     * endpoint ini untuk menelusuri yang lama — dan riwayat ISO bisa panjang, jadi
     * mengirim semuanya sekaligus bukan pilihan.
     */
    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        return Map.of(
                "total", state.countLogs(id),
                "rows", state.recentLogs(id, limit, offset));
    }

    /**
     * Mengosongkan riwayat pesan — menghapus di DATABASE, bukan sekadar membersihkan
     * tampilan. Rekening, kartu, dan profil tak tersentuh.
     */
    @DeleteMapping("/{id}/logs")
    public Map<String, Object> clearLogs(@PathVariable UUID id) {
        return Map.of("deleted", state.clearLogs(id));
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

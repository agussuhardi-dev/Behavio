package id.behavio.bank.platform.web.admin;

import id.behavio.bank.platform.core.port.PartnerAdmin;
import id.behavio.bank.platform.web.ProductRegistry;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: kelola Partner (pemanggil + kunci signature) — generik untuk semua produk.
 * Partner bank & partner QRIS tersimpan di schema masing-masing; yang membedakan hanya
 * segmen {@code {product}} di URL.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:bank}/simulators/{id}/partners")
public class PartnerAdminController {

    private final ProductRegistry products;

    public PartnerAdminController(ProductRegistry products) {
        this.products = products;
    }

    @GetMapping
    public List<PartnerAdmin.PartnerView> list(@PathVariable String product, @PathVariable UUID id) {
        return products.require(product).partners().listPartners(id);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String product, @PathVariable UUID id,
                                    @RequestBody Map<String, String> body) {
        String partnerId = body.get("partnerId");
        if (partnerId == null || partnerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'partnerId' wajib"));
        }
        try {
            UUID rowId = products.require(product).partners()
                    .createPartner(id, partnerId, body.get("publicKeyPem"), body.get("clientSecret"));
            return ResponseEntity.ok(Map.of("id", rowId, "partnerId", partnerId));
        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException e) {
            // 409 (bukan 400): duplikat = konflik. Sengaja lokal, bukan lewat handler global.
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{partnerRowId}")
    public ResponseEntity<?> delete(@PathVariable String product, @PathVariable UUID id,
                                    @PathVariable UUID partnerRowId) {
        products.require(product).partners().deletePartner(id, partnerRowId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}

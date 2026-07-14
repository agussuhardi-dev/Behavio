package id.behavio.web.admin;

import id.behavio.core.port.AccountAdmin;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: kelola Partner (nasabah/pemanggil) & Account (rekening) dari
 * dashboard — melengkapi provisioning baseline otomatis saat simulator dibuat.
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}")
public class AccountAdminController {

    private final AccountAdmin admin;

    public AccountAdminController(AccountAdmin admin) {
        this.admin = admin;
    }

    @GetMapping("/partners")
    public List<AccountAdmin.PartnerView> listPartners(@PathVariable UUID id) {
        return admin.listPartners(id);
    }

    @PostMapping("/partners")
    public ResponseEntity<?> createPartner(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String partnerId = body.get("partnerId");
        if (partnerId == null || partnerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'partnerId' wajib"));
        }
        try {
            UUID rowId = admin.createPartner(id, partnerId, body.get("publicKeyPem"), body.get("clientSecret"));
            return ResponseEntity.ok(Map.of("id", rowId, "partnerId", partnerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/partners/{partnerRowId}")
    public ResponseEntity<?> deletePartner(@PathVariable UUID id, @PathVariable UUID partnerRowId) {
        admin.deletePartner(id, partnerRowId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @GetMapping("/accounts")
    public List<AccountAdmin.AccountView> listAccounts(@PathVariable UUID id) {
        return admin.listAccounts(id);
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID partnerRowId = uuid(body.get("partnerId"));
        String accountNo = str(body.get("accountNo"));
        if (partnerRowId == null || accountNo == null || accountNo.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field 'partnerId' dan 'accountNo' wajib"));
        }
        BigDecimal balance = decimal(body.get("balance"));
        try {
            UUID accId = admin.createAccount(id, partnerRowId, accountNo, str(body.get("holderName")), balance);
            return ResponseEntity.ok(Map.of("id", accId, "accountNo", accountNo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> setBalance(@PathVariable UUID id, @PathVariable UUID accountId,
                                        @RequestBody Map<String, Object> body) {
        try {
            admin.setBalance(id, accountId, decimal(body.get("balance")));
            return ResponseEntity.ok(Map.of("status", "updated"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<?> deleteAccount(@PathVariable UUID id, @PathVariable UUID accountId) {
        admin.deleteAccount(id, accountId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static UUID uuid(Object v) {
        try { return v == null ? null : UUID.fromString(v.toString()); }
        catch (IllegalArgumentException e) { return null; }
    }
    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Nilai saldo tidak valid"); }
    }
}

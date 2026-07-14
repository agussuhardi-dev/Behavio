package id.behavio.bank.web.admin;

import id.behavio.bank.port.AccountAdmin;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API: kelola Account (rekening + saldo) dari dashboard — melengkapi provisioning
 * baseline otomatis saat simulator dibuat.
 *
 * Khusus produk BANK, maka path-nya {@code /bank/...}: profil QRIS tak punya rekening.
 * Pengelolaan partner pindah ke {@code PartnerAdminController} yang generik, karena
 * kedua produk sama-sama punya partner dengan kredensial SNAP sendiri.
 */
@RestController
@RequestMapping("/api/admin/v1/bank/simulators/{id}")
public class AccountAdminController {

    private final AccountAdmin admin;

    public AccountAdminController(AccountAdmin admin) {
        this.admin = admin;
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
        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException e) {
            // 409 (bukan 400): ini konflik/duplikat. Sengaja ditangani lokal, bukan
            // diserahkan ke ApiExceptionHandler global yang membalas 400.
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> setBalance(@PathVariable UUID id, @PathVariable UUID accountId,
                                        @RequestBody Map<String, Object> body) {
        admin.setBalance(id, accountId, decimal(body.get("balance")));
        return ResponseEntity.ok(Map.of("status", "updated"));
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

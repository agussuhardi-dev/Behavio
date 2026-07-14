package id.behavio.web.admin;

import id.behavio.core.domain.VirtualAccount;
import id.behavio.web.VirtualAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API untuk memantau Virtual Account & memicu Payment Notification
 * (design.md Lampiran A2.3) — mensimulasikan "VA dibayar" dari dashboard.
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/virtual-accounts")
public class VirtualAccountAdminController {

    private final VirtualAccountService service;

    public VirtualAccountAdminController(VirtualAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<VaView> list(@PathVariable UUID id) {
        return service.list(id).stream().map(VaView::from).toList();
    }

    @PostMapping("/{vaNo}/pay")
    public ResponseEntity<?> markPaid(@PathVariable UUID id, @PathVariable String vaNo) {
        VirtualAccountService.PayResult r = service.markPaid(id, vaNo);
        if (!r.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "virtualAccountNo", vaNo,
                "status", "PAID",
                "webhookSent", r.webhookSent(),
                "note", r.reason()));
    }

    /** Ringkasan VA untuk tampilan dashboard. */
    record VaView(String virtualAccountNo, String virtualAccountName, String amount,
                 String currency, String status, String trxId, boolean hasCallback) {
        static VaView from(VirtualAccount va) {
            return new VaView(va.virtualAccountNo(), va.virtualAccountName(),
                    va.totalAmount() == null ? "0.00" : va.totalAmount().toPlainString(),
                    va.currency(), va.status().name(), va.trxId(),
                    va.callbackUrl() != null && !va.callbackUrl().isBlank());
        }
    }
}

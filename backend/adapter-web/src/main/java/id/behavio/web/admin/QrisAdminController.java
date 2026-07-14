package id.behavio.web.admin;

import id.behavio.core.domain.QrisTransaction;
import id.behavio.web.QrisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API untuk memantau QR & memicu Payment Notify (design.md Lampiran A3.4)
 * — mensimulasikan "QR dipindai & dibayar pelanggan" dari dashboard.
 */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/qris")
public class QrisAdminController {

    private final QrisService service;

    public QrisAdminController(QrisService service) {
        this.service = service;
    }

    @GetMapping
    public List<QrView> list(@PathVariable UUID id) {
        return service.list(id).stream().map(QrView::from).toList();
    }

    @PostMapping("/{referenceNo}/pay")
    public ResponseEntity<?> markPaid(@PathVariable UUID id, @PathVariable String referenceNo,
                                      @RequestBody(required = false) Map<String, Object> body) {
        BigDecimal amountOverride = null;
        if (body != null && body.get("amount") != null) {
            try {
                amountOverride = new BigDecimal(body.get("amount").toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Nilai amount tidak valid"));
            }
        }
        QrisService.PayResult r = service.markPaid(id, referenceNo, amountOverride);
        if (!r.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "referenceNo", referenceNo,
                "webhookSent", r.webhookSent(),
                "note", r.reason()));
    }

    /** Ringkasan QR untuk tampilan dashboard. */
    record QrView(String referenceNo, String partnerReferenceNo, String merchantId, String qrType,
                 String amount, String currency, String status, boolean hasCallback) {
        static QrView from(QrisTransaction qr) {
            BigDecimal shown = qr.status().name().equals("PAID") ? qr.paidAmount() : qr.amount();
            return new QrView(qr.referenceNo(), qr.partnerReferenceNo(), qr.merchantId(),
                    qr.qrType().name(), shown == null ? null : shown.toPlainString(), qr.currency(),
                    qr.status().name(), qr.callbackUrl() != null && !qr.callbackUrl().isBlank());
        }
    }
}

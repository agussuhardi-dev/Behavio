package id.behavio.qris.web.admin;

import id.behavio.qris.domain.QrisTransaction;
import id.behavio.qris.web.QrisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API untuk memantau QR & memicu Payment Notify (design.md Lampiran A3.4)
 * — mensimulasikan "QR dipindai & dibayar pelanggan" dari dashboard.
 *
 * Khusus produk QRIS, sehingga berada di bawah segmen {@code /qris/} seperti Admin API
 * lain sejak pemisahan produk (design.md §3.4).
 */
@RestController
@RequestMapping("/api/admin/v1/qris/simulators/{id}/qris")
public class QrisAdminController {

    private final QrisService service;

    public QrisAdminController(QrisService service) {
        this.service = service;
    }

    /** Daftar QR terbaru-dulu, dipaginasi (dashboard memuat ulang tiap ada request QRIS masuk). */
    @GetMapping
    public PageView list(@PathVariable UUID id,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "10") int size) {
        QrisService.QrisPage p = service.list(id, page, size);
        return new PageView(p.items().stream().map(QrView::from).toList(), p.total(), p.page(), p.size());
    }

    record PageView(List<QrView> items, int total, int page, int size) {}

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

    /**
     * Kirim ulang Payment Notify memakai status AKTIF QR — retry/test (design.md §9.2).
     * Tidak mengubah status: pengiriman normal sudah otomatis saat QR dibayar.
     */
    @PostMapping("/{referenceNo}/resend-notification")
    public ResponseEntity<?> resend(@PathVariable UUID id, @PathVariable String referenceNo) {
        QrisService.PayResult r = service.resendNotification(id, referenceNo);
        if (!r.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "referenceNo", referenceNo,
                "webhookSent", r.webhookSent(),
                "note", r.reason()));
    }

    @PostMapping("/{referenceNo}/expire")
    public ResponseEntity<?> expire(@PathVariable UUID id, @PathVariable String referenceNo) {
        QrisService.PayResult r = service.adminExpire(id, referenceNo);
        if (!r.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("referenceNo", referenceNo, "note", r.reason()));
    }

    /**
     * Ringkasan QR untuk tampilan dashboard.
     *
     * Tanpa {@code hasCallback}: sejak §9.1 URL notifikasi milik PARTNER (registrasi),
     * bukan milik QR — menandainya per-QR jadi menyesatkan.
     */
    record QrView(String referenceNo, String partnerReferenceNo, String merchantId, String qrType,
                 String amount, String currency, String status) {
        static QrView from(QrisTransaction qr) {
            BigDecimal shown = qr.status().name().equals("PAID") ? qr.paidAmount() : qr.amount();
            return new QrView(qr.referenceNo(), qr.partnerReferenceNo(), qr.merchantId(),
                    qr.qrType().name(), shown == null ? null : shown.toPlainString(), qr.currency(),
                    qr.status().name());
        }
    }
}

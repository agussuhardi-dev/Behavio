package id.behavio.core.blueprint;

import java.util.List;

/**
 * Katalog operasi SNAP yang didukung simulator, dengan path DEFAULT (standar ASPI).
 * {@code key} adalah identitas stabil (tak berubah) — path-nya SENDIRI dapat
 * di-custom per-simulator dari dashboard (design.md §2 override path/URL), karena
 * bank berbeda kerap punya path/versi berbeda (mis. BRI memakai
 * "/intrabank/snap/v2.0/transfer-intrabank", bukan "/v1.0/transfer-intrabank").
 */
public final class SnapOperations {

    private SnapOperations() {}

    public record Op(String key, String method, String defaultPath, String label) {}

    public static final List<Op> ALL = List.of(
            new Op("access-token", "POST", "/v1.0/access-token/b2b", "Access Token B2B"),
            new Op("transfer", "POST", "/v1.0/transfer-intrabank", "Transfer Intrabank"),
            new Op("va-create", "POST", "/v1.0/transfer-va/create-va", "Virtual Account — Create"),
            new Op("va-status", "POST", "/v1.0/transfer-va/status", "Virtual Account — Inquiry Status"),
            new Op("va-delete", "DELETE", "/v1.0/transfer-va/delete-va", "Virtual Account — Delete"),
            new Op("qris-generate", "POST", "/v1.0/qr/qr-mpm-generate", "QRIS — Generate"),
            new Op("qris-query", "POST", "/v1.0/qr/qr-mpm-query", "QRIS — Query Status"),
            new Op("qris-refund", "POST", "/v1.0/qr/qr-mpm-refund", "QRIS — Refund"),
            new Op("qris-cancel", "POST", "/v1.0/qr/qr-mpm-cancel", "QRIS — Cancel Payment"),
            new Op("qris-decode", "POST", "/v1.0/qr/qr-mpm-decode", "QRIS — Decode QR"),
            new Op("qris-payment", "POST", "/v1.0/qr/qr-mpm-payment", "QRIS — Payment H2H"),
            new Op("qris-apply-ott", "POST", "/v1.0/qr/apply-ott", "QRIS — Apply OTT")
    );

    public static Op byKey(String key) {
        return ALL.stream().filter(o -> o.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Operasi tak dikenal: " + key));
    }
}

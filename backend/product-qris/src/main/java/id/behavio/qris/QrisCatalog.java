package id.behavio.qris;

import id.behavio.core.product.Operation;
import id.behavio.core.product.ProductCatalog;
import id.behavio.core.rule.Scenario;
import id.behavio.qris.blueprint.QrisApplyOttBlueprint;
import id.behavio.qris.blueprint.QrisCancelBlueprint;
import id.behavio.qris.blueprint.QrisDecodeBlueprint;
import id.behavio.qris.blueprint.QrisMpmBlueprint;
import id.behavio.qris.blueprint.QrisPaymentBlueprint;
import id.behavio.qris.blueprint.QrisQueryBlueprint;
import id.behavio.qris.blueprint.QrisRefundBlueprint;

import java.util.List;
import java.util.Optional;

/**
 * Katalog produk QRIS (PJP): operasi SNAP MPM + preset blueprint-nya.
 *
 * Profil QRIS berdiri sendiri — punya {@code access-token} sendiri karena partner &
 * kredensialnya juga milik PJP ini, bukan pinjaman dari profil bank.
 *
 * Tak punya {@code actionCodec}: QRIS tidak memutasi saldo rekening, jadi daftar aksi
 * scenario-nya selalu kosong dan default {@code ActionCodec.NONE} sudah tepat.
 */
public final class QrisCatalog implements ProductCatalog {

    public static final String KEY = "qris";

    private static final List<Operation> OPERATIONS = List.of(
            Operation.plain("access-token", "POST", "/v1.0/access-token/b2b", "Access Token B2B"),
            new Operation("qris-generate", QrisMpmBlueprint.METHOD, QrisMpmBlueprint.PATH,
                    "QRIS — Generate", QrisMpmBlueprint.SCENARIO_NAMES),
            Operation.plain("qris-query", QrisQueryBlueprint.METHOD, QrisQueryBlueprint.PATH, "QRIS — Query Status"),
            Operation.plain("qris-refund", QrisRefundBlueprint.METHOD, QrisRefundBlueprint.PATH, "QRIS — Refund"),
            Operation.plain("qris-cancel", QrisCancelBlueprint.METHOD, QrisCancelBlueprint.PATH, "QRIS — Cancel Payment"),
            Operation.plain("qris-decode", QrisDecodeBlueprint.METHOD, QrisDecodeBlueprint.PATH, "QRIS — Decode QR"),
            Operation.plain("qris-payment", QrisPaymentBlueprint.METHOD, QrisPaymentBlueprint.PATH, "QRIS — Payment H2H"),
            Operation.plain("qris-apply-ott", QrisApplyOttBlueprint.METHOD, QrisApplyOttBlueprint.PATH, "QRIS — Apply OTT"));

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "QRIS (PJP)";
    }

    @Override
    public List<Operation> operations() {
        return OPERATIONS;
    }

    /**
     * Selain kunci operasi, menerima alias {@code "qris"} → {@code "qris-generate"}: nilai
     * itu dipakai Admin API/dashboard sejak sebelum pemisahan (mis.
     * {@code .../scenarios/{name}/definition?product=qris}), jadi tetap didukung agar URL
     * yang sudah beredar tak putus.
     */
    @Override
    public Optional<Operation> byKey(String operationKey) {
        String k = operationKey == null ? "" : operationKey.trim().toLowerCase();
        return ProductCatalog.super.byKey("qris".equals(k) ? "qris-generate" : k);
    }

    @Override
    public Optional<Scenario> blueprint(String operationKey, String scenarioName) {
        String op = operationKey == null ? "" : operationKey.trim().toLowerCase();
        return switch (op) {
            case "qris", "qris-generate" -> Optional.of(QrisMpmBlueprint.byName(scenarioName));
            case "qris-query" -> Optional.of(QrisQueryBlueprint.normal());
            case "qris-refund" -> Optional.of(QrisRefundBlueprint.normal());
            case "qris-cancel" -> Optional.of(QrisCancelBlueprint.normal());
            case "qris-decode" -> Optional.of(QrisDecodeBlueprint.normal());
            case "qris-payment" -> Optional.of(QrisPaymentBlueprint.normal());
            case "qris-apply-ott" -> Optional.of(QrisApplyOttBlueprint.normal());
            default -> Optional.empty();   // access-token berlogika tetap
        };
    }
}

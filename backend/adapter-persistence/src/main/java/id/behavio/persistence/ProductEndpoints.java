package id.behavio.persistence;

import id.behavio.core.blueprint.*;

/**
 * Resolusi {@code product} (kunci pendek dipakai Admin API/dashboard) → (method, path)
 * endpoint sebenarnya. Menghindari path HTTP mentah bocor ke rute REST admin.
 */
final class ProductEndpoints {

    private ProductEndpoints() {}

    record Endpoint(String method, String path) {}

    static Endpoint resolve(String product) {
        return switch (product == null ? "transfer" : product.trim().toLowerCase()) {
            case "qris" -> new Endpoint(QrisMpmBlueprint.METHOD, QrisMpmBlueprint.PATH);
            case "qris-query" -> new Endpoint(QrisQueryBlueprint.METHOD, QrisQueryBlueprint.PATH);
            case "qris-refund" -> new Endpoint(QrisRefundBlueprint.METHOD, QrisRefundBlueprint.PATH);
            case "qris-cancel" -> new Endpoint(QrisCancelBlueprint.METHOD, QrisCancelBlueprint.PATH);
            case "qris-decode" -> new Endpoint(QrisDecodeBlueprint.METHOD, QrisDecodeBlueprint.PATH);
            case "qris-payment" -> new Endpoint(QrisPaymentBlueprint.METHOD, QrisPaymentBlueprint.PATH);
            case "qris-apply-ott" -> new Endpoint(QrisApplyOttBlueprint.METHOD, QrisApplyOttBlueprint.PATH);
            default -> new Endpoint(TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH);
        };
    }
}

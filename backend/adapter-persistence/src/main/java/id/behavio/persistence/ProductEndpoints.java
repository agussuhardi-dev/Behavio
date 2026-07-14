package id.behavio.persistence;

import id.behavio.core.blueprint.QrisMpmBlueprint;
import id.behavio.core.blueprint.TransferIntrabankBlueprint;

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
            default -> new Endpoint(TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH);
        };
    }
}

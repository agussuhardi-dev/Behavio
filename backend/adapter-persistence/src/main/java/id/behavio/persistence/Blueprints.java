package id.behavio.persistence;

import id.behavio.core.blueprint.QrisMpmBlueprint;
import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.rule.Scenario;

/** Peta (product, nama scenario) → preset Blueprint. Titik awal sebelum di-override. */
final class Blueprints {

    private Blueprints() {}

    static Scenario byName(String product, String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        if ("qris".equalsIgnoreCase(product)) {
            return switch (key) {
                case "merchant diblokir" -> QrisMpmBlueprint.merchantBlocked();
                case "service down" -> QrisMpmBlueprint.serviceDown();
                default -> QrisMpmBlueprint.normal();
            };
        }
        return switch (key) {
            case "saldo kurang" -> TransferIntrabankBlueprint.forcedInsufficient();
            case "limit" -> TransferIntrabankBlueprint.limit();
            case "bank down" -> TransferIntrabankBlueprint.bankDown();
            case "timeout" -> TransferIntrabankBlueprint.timeout();
            case "commit then drop" -> TransferIntrabankBlueprint.commitThenDrop();
            case "malformed" -> TransferIntrabankBlueprint.malformed();
            case "async callback" -> TransferIntrabankBlueprint.asyncCallback();
            default -> TransferIntrabankBlueprint.normal();
        };
    }
}

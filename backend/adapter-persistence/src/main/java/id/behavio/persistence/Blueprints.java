package id.behavio.persistence;

import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.rule.Scenario;

/** Peta nama scenario (DB) → preset Blueprint. Titik awal sebelum di-override. */
final class Blueprints {

    private Blueprints() {}

    static Scenario byName(String name) {
        return switch (name == null ? "" : name.trim().toLowerCase()) {
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

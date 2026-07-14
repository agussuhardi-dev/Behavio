package id.behavio.persistence;

import id.behavio.core.blueprint.*;
import id.behavio.core.rule.Scenario;

/** Peta (product, nama scenario) → preset Blueprint. Titik awal sebelum di-override. */
final class Blueprints {

    private Blueprints() {}

    /**
     * Apakah product punya preset blueprint sendiri. Dipakai supaya endpoint tanpa baris
     * scenario tetap memakai preset-nya (bukan jatuh ke {@code default} = transfer).
     */
    static boolean supports(String product) {
        if (product == null) return false;
        return switch (product.trim().toLowerCase()) {
            case "transfer", "qris", "qris-generate", "qris-query", "qris-refund",
                 "qris-cancel", "qris-decode", "qris-payment", "qris-apply-ott" -> true;
            default -> false;
        };
    }

    static Scenario byName(String product, String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        String p = product == null ? "transfer" : product.trim().toLowerCase();
        return switch (p) {
            // "qris-generate" = kunci operasi di tabel endpoints (SnapOperations);
            // "qris" = nama product yang dipakai dashboard. Keduanya endpoint yang sama.
            case "qris", "qris-generate" -> switch (key) {
                case "merchant diblokir" -> QrisMpmBlueprint.merchantBlocked();
                case "service down" -> QrisMpmBlueprint.serviceDown();
                default -> QrisMpmBlueprint.normal();
            };
            case "qris-query" -> QrisQueryBlueprint.normal();
            case "qris-refund" -> QrisRefundBlueprint.normal();
            case "qris-cancel" -> QrisCancelBlueprint.normal();
            case "qris-decode" -> QrisDecodeBlueprint.normal();
            case "qris-payment" -> QrisPaymentBlueprint.normal();
            case "qris-apply-ott" -> QrisApplyOttBlueprint.normal();
            default -> switch (key) {
                case "saldo kurang" -> TransferIntrabankBlueprint.forcedInsufficient();
                case "limit" -> TransferIntrabankBlueprint.limit();
                case "bank down" -> TransferIntrabankBlueprint.bankDown();
                case "timeout" -> TransferIntrabankBlueprint.timeout();
                case "commit then drop" -> TransferIntrabankBlueprint.commitThenDrop();
                case "malformed" -> TransferIntrabankBlueprint.malformed();
                case "async callback" -> TransferIntrabankBlueprint.asyncCallback();
                default -> TransferIntrabankBlueprint.normal();
            };
        };
    }
}

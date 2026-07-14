package id.behavio.bank;

import id.behavio.bank.blueprint.AccountInquiryInternalBlueprint;
import id.behavio.bank.blueprint.BalanceInquiryBlueprint;
import id.behavio.bank.blueprint.InterbankTransferBlueprint;
import id.behavio.bank.blueprint.TransactionHistoryListBlueprint;
import id.behavio.bank.blueprint.TransferIntrabankBlueprint;
import id.behavio.bank.rule.BankActionCodec;
import id.behavio.core.product.ActionCodec;
import id.behavio.core.product.Operation;
import id.behavio.core.product.ProductCatalog;
import id.behavio.core.rule.Scenario;

import java.util.List;
import java.util.Optional;

/**
 * Katalog produk BANK: operasi SNAP yang dilayani profil bank + preset blueprint-nya.
 *
 * Satu-satunya tempat "apa itu bank" didefinisikan. Menggantikan tiga peta terpisah
 * sebelum pemisahan ({@code SnapOperations}, {@code Blueprints}, {@code ProductEndpoints})
 * yang masing-masing mencampur operasi bank & QRIS dan harus dijaga tetap sinkron
 * satu sama lain.
 */
public final class BankCatalog implements ProductCatalog {

    public static final String KEY = "bank";

    /** Katalog ASPI untuk transfer — 8 scenario, termasuk fault (design.md §4.2). */
    private static final List<String> TRANSFER_SCENARIOS = List.of(
            "Normal", "Saldo Kurang", "Limit", "Bank Down", "Timeout",
            "Commit Then Drop", "Malformed", "Async Callback");

    private static final List<String> INTERBANK_SCENARIOS = List.of("Normal", "Saldo Kurang", "Limit");

    private static final List<String> ONLY_NORMAL = List.of("Normal");

    private static final List<Operation> OPERATIONS = List.of(
            Operation.plain("access-token", "POST", "/v1.0/access-token/b2b", "Access Token B2B"),
            new Operation("balance-inquiry", BalanceInquiryBlueprint.METHOD, BalanceInquiryBlueprint.PATH,
                    "Balance Inquiry", ONLY_NORMAL),
            new Operation("account-inquiry-internal", AccountInquiryInternalBlueprint.METHOD,
                    AccountInquiryInternalBlueprint.PATH, "Internal Account Inquiry", ONLY_NORMAL),
            new Operation("transaction-history-list", TransactionHistoryListBlueprint.METHOD,
                    TransactionHistoryListBlueprint.PATH, "Transaction History List", ONLY_NORMAL),
            new Operation("transfer", TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH,
                    "Transfer Intrabank", TRANSFER_SCENARIOS),
            new Operation("transfer-interbank", InterbankTransferBlueprint.METHOD, InterbankTransferBlueprint.PATH,
                    "Transfer Interbank", INTERBANK_SCENARIOS),
            Operation.plain("va-create", "POST", "/v1.0/transfer-va/create-va", "Virtual Account — Create"),
            Operation.plain("va-status", "POST", "/v1.0/transfer-va/status", "Virtual Account — Inquiry Status"),
            Operation.plain("va-delete", "DELETE", "/v1.0/transfer-va/delete-va", "Virtual Account — Delete"));

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Bank";
    }

    @Override
    public List<Operation> operations() {
        return OPERATIONS;
    }

    @Override
    public Optional<Scenario> blueprint(String operationKey, String scenarioName) {
        String op = operationKey == null ? "" : operationKey.trim().toLowerCase();
        String sc = scenarioName == null ? "" : scenarioName.trim().toLowerCase();
        return switch (op) {
            case "balance-inquiry" -> Optional.of(BalanceInquiryBlueprint.normal());
            case "account-inquiry-internal" -> Optional.of(AccountInquiryInternalBlueprint.normal());
            case "transaction-history-list" -> Optional.of(TransactionHistoryListBlueprint.normal());
            case "transfer-interbank" -> Optional.of(switch (sc) {
                case "saldo kurang" -> InterbankTransferBlueprint.forcedInsufficient();
                case "limit" -> InterbankTransferBlueprint.limit();
                default -> InterbankTransferBlueprint.normal();
            });
            case "transfer" -> Optional.of(switch (sc) {
                case "saldo kurang" -> TransferIntrabankBlueprint.forcedInsufficient();
                case "limit" -> TransferIntrabankBlueprint.limit();
                case "bank down" -> TransferIntrabankBlueprint.bankDown();
                case "timeout" -> TransferIntrabankBlueprint.timeout();
                case "commit then drop" -> TransferIntrabankBlueprint.commitThenDrop();
                case "malformed" -> TransferIntrabankBlueprint.malformed();
                case "async callback" -> TransferIntrabankBlueprint.asyncCallback();
                default -> TransferIntrabankBlueprint.normal();
            });
            // access-token & VA berlogika tetap — responsnya tidak lewat scenario/rule.
            default -> Optional.empty();
        };
    }

    @Override
    public ActionCodec actionCodec() {
        return BankActionCodec.INSTANCE;
    }
}

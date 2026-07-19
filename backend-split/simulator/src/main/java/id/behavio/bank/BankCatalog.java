package id.behavio.bank;

import id.behavio.bank.blueprint.AccountInquiryInternalBlueprint;
import id.behavio.bank.blueprint.AccessTokenBlueprint;
import id.behavio.bank.blueprint.BalanceInquiryBlueprint;
import id.behavio.bank.blueprint.ExternalAccountInquiryBlueprint;
import id.behavio.bank.blueprint.InterbankTransferBlueprint;
import id.behavio.bank.blueprint.TransactionHistoryListBlueprint;
import id.behavio.bank.blueprint.TransferIntrabankBlueprint;
import id.behavio.bank.blueprint.VirtualAccountCreateBlueprint;
import id.behavio.bank.blueprint.VirtualAccountDeleteBlueprint;
import id.behavio.bank.blueprint.VirtualAccountStatusBlueprint;
import id.behavio.bank.rule.BankActionCodec;
import id.behavio.bank.platform.core.product.ActionCodec;
import id.behavio.bank.platform.core.product.HeaderSpec;
import id.behavio.bank.platform.core.product.Operation;
import id.behavio.bank.platform.core.product.ProductCatalog;
import id.behavio.bank.platform.core.rule.Scenario;

import java.util.List;
import java.util.Map;
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

    private static final List<String> INTERBANK_SCENARIOS = List.of(
            "Normal", "Saldo Kurang", "Limit", "Bank Down", "Timeout");

    private static final List<String> INQUIRY_SCENARIOS = List.of("Normal", "Bank Down", "Timeout");

    private static final List<Operation> OPERATIONS = List.of(
            new Operation("access-token", AccessTokenBlueprint.METHOD, AccessTokenBlueprint.PATH,
                    "Access Token B2B", INQUIRY_SCENARIOS),
            new Operation("balance-inquiry", BalanceInquiryBlueprint.METHOD, BalanceInquiryBlueprint.PATH,
                    "Balance Inquiry", INQUIRY_SCENARIOS),
            new Operation("account-inquiry-internal", AccountInquiryInternalBlueprint.METHOD,
                    AccountInquiryInternalBlueprint.PATH, "Internal Account Inquiry", INQUIRY_SCENARIOS),
            new Operation("account-inquiry-external", ExternalAccountInquiryBlueprint.METHOD,
                    ExternalAccountInquiryBlueprint.PATH, "External Account Inquiry", INQUIRY_SCENARIOS),
            new Operation("transaction-history-list", TransactionHistoryListBlueprint.METHOD,
                    TransactionHistoryListBlueprint.PATH, "Transaction History List", INQUIRY_SCENARIOS),
            new Operation("transfer", TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH,
                    "Transfer Intrabank", TRANSFER_SCENARIOS),
            new Operation("transfer-interbank", InterbankTransferBlueprint.METHOD, InterbankTransferBlueprint.PATH,
                    "Transfer Interbank", INTERBANK_SCENARIOS),
            new Operation("va-create", VirtualAccountCreateBlueprint.METHOD, VirtualAccountCreateBlueprint.PATH,
                    "Virtual Account — Create", INQUIRY_SCENARIOS),
            new Operation("va-status", VirtualAccountStatusBlueprint.METHOD, VirtualAccountStatusBlueprint.PATH,
                    "Virtual Account — Inquiry Status", INQUIRY_SCENARIOS),
            new Operation("va-delete", VirtualAccountDeleteBlueprint.METHOD, VirtualAccountDeleteBlueprint.PATH,
                    "Virtual Account — Delete", INQUIRY_SCENARIOS));

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
            case "access-token" -> Optional.of(switch (sc) {
                case "bank down" -> AccessTokenBlueprint.bankDown();
                case "timeout" -> AccessTokenBlueprint.timeout();
                default -> AccessTokenBlueprint.normal();
            });
            case "balance-inquiry" -> Optional.of(switch (sc) {
                case "bank down" -> BalanceInquiryBlueprint.bankDown();
                case "timeout" -> BalanceInquiryBlueprint.timeout();
                default -> BalanceInquiryBlueprint.normal();
            });
            case "account-inquiry-internal" -> Optional.of(switch (sc) {
                case "bank down" -> AccountInquiryInternalBlueprint.bankDown();
                case "timeout" -> AccountInquiryInternalBlueprint.timeout();
                default -> AccountInquiryInternalBlueprint.normal();
            });
            case "account-inquiry-external" -> Optional.of(switch (sc) {
                case "bank down" -> ExternalAccountInquiryBlueprint.bankDown();
                case "timeout" -> ExternalAccountInquiryBlueprint.timeout();
                default -> ExternalAccountInquiryBlueprint.normal();
            });
            case "transaction-history-list" -> Optional.of(switch (sc) {
                case "bank down" -> TransactionHistoryListBlueprint.bankDown();
                case "timeout" -> TransactionHistoryListBlueprint.timeout();
                default -> TransactionHistoryListBlueprint.normal();
            });
            case "transfer-interbank" -> Optional.of(switch (sc) {
                case "saldo kurang" -> InterbankTransferBlueprint.forcedInsufficient();
                case "limit" -> InterbankTransferBlueprint.limit();
                case "bank down" -> InterbankTransferBlueprint.bankDown();
                case "timeout" -> InterbankTransferBlueprint.timeout();
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
            case "va-create" -> Optional.of(switch (sc) {
                case "bank down" -> VirtualAccountCreateBlueprint.bankDown();
                case "timeout" -> VirtualAccountCreateBlueprint.timeout();
                default -> VirtualAccountCreateBlueprint.normal();
            });
            case "va-status" -> Optional.of(switch (sc) {
                case "bank down" -> VirtualAccountStatusBlueprint.bankDown();
                case "timeout" -> VirtualAccountStatusBlueprint.timeout();
                default -> VirtualAccountStatusBlueprint.normal();
            });
            case "va-delete" -> Optional.of(switch (sc) {
                case "bank down" -> VirtualAccountDeleteBlueprint.bankDown();
                case "timeout" -> VirtualAccountDeleteBlueprint.timeout();
                default -> VirtualAccountDeleteBlueprint.normal();
            });
            default -> Optional.empty();
        };
    }

    @Override
    public ActionCodec actionCodec() {
        return BankActionCodec.INSTANCE;
    }

    /** Access token memakai pola RSA (X-CLIENT-KEY), bukan Bearer+HMAC (Lampiran A.1). */
    @Override
    public List<HeaderSpec> requestHeaders(String operationKey) {
        return "access-token".equalsIgnoreCase(operationKey == null ? "" : operationKey.trim())
                ? HeaderSpec.snapAccessToken()
                : HeaderSpec.snapTransactional();
    }

    /** Contoh request per operasi untuk export OpenAPI (design.md §15.5). */
    @Override
    public Optional<Map<String, Object>> requestExample(String operationKey) {
        String op = operationKey == null ? "" : operationKey.trim().toLowerCase();
        return switch (op) {
            case "access-token" -> Optional.of(AccessTokenBlueprint.requestExample());
            case "balance-inquiry" -> Optional.of(BalanceInquiryBlueprint.requestExample());
            case "account-inquiry-internal" -> Optional.of(AccountInquiryInternalBlueprint.requestExample());
            case "account-inquiry-external" -> Optional.of(ExternalAccountInquiryBlueprint.requestExample());
            case "transaction-history-list" -> Optional.of(TransactionHistoryListBlueprint.requestExample());
            case "transfer" -> Optional.of(TransferIntrabankBlueprint.requestExample());
            case "transfer-interbank" -> Optional.of(InterbankTransferBlueprint.requestExample());
            case "va-create" -> Optional.of(VirtualAccountCreateBlueprint.requestExample());
            case "va-status" -> Optional.of(VirtualAccountStatusBlueprint.requestExample());
            case "va-delete" -> Optional.of(VirtualAccountDeleteBlueprint.requestExample());
            default -> Optional.empty();
        };
    }
}

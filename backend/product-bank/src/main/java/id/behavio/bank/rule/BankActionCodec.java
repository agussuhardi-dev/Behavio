package id.behavio.bank.rule;

import id.behavio.bank.domain.TransactionStatus;
import id.behavio.core.product.ActionCodec;
import id.behavio.core.rule.Action;

import java.util.Map;
import java.util.Optional;

/**
 * Simpan/muat {@link BankAction} ke bentuk netral, agar definisi scenario bank tetap
 * dapat di-edit dari dashboard (design.md §8) tanpa mesin penyimpan mengenal tipenya.
 *
 * Format JSON-nya dipertahankan persis seperti sebelum pemisahan
 * ({@code {"kind":"debit","accountNoField":"…","amountField":"…"}}) supaya definisi
 * custom yang sudah ditulis user tetap terbaca.
 */
public final class BankActionCodec implements ActionCodec {

    public static final BankActionCodec INSTANCE = new BankActionCodec();

    private BankActionCodec() {}

    @Override
    public Optional<Action> parse(String kind, Map<String, String> a) {
        if (kind == null) return Optional.empty();
        return switch (kind.trim()) {
            case "debit" -> Optional.of(new BankAction.Debit(str(a, "accountNoField"), str(a, "amountField")));
            case "credit" -> Optional.of(new BankAction.Credit(str(a, "accountNoField"), str(a, "amountField")));
            case "createTransaction" -> Optional.of(new BankAction.CreateTransaction(
                    TransactionStatus.valueOf(a.getOrDefault("status", "SUCCESS"))));
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<Encoded> encode(Action action) {
        if (!(action instanceof BankAction ba)) return Optional.empty();
        return Optional.of(switch (ba) {
            case BankAction.Debit d -> new Encoded("debit",
                    Map.of("accountNoField", d.accountNoField(), "amountField", d.amountField()));
            case BankAction.Credit c -> new Encoded("credit",
                    Map.of("accountNoField", c.accountNoField(), "amountField", c.amountField()));
            case BankAction.CreateTransaction ct -> new Encoded("createTransaction",
                    Map.of("status", ct.status().name()));
        });
    }

    private static String str(Map<String, String> a, String key) {
        return a.getOrDefault(key, "");
    }
}

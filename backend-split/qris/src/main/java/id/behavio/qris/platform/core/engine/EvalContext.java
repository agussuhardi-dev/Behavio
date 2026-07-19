package id.behavio.qris.platform.core.engine;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

/**
 * Konteks evaluasi rule: field request (sudah dinormalkan) + akses saldo account.
 * Murni — tanpa I/O; lookup saldo di-inject sebagai fungsi agar engine tetap
 * dapat diuji tanpa DB.
 */
public final class EvalContext {

    private final Map<String, Object> fields;
    private final Function<String, BigDecimal> balanceLookup;

    public EvalContext(Map<String, Object> fields, Function<String, BigDecimal> balanceLookup) {
        this.fields = fields;
        this.balanceLookup = balanceLookup;
    }

    public Object field(String path) {
        return fields.get(path);
    }

    public Map<String, Object> fields() {
        return fields;
    }

    /** Saldo account (nomor) atau {@code null} bila tak ada. */
    public BigDecimal balanceOf(String accountNo) {
        return accountNo == null ? null : balanceLookup.apply(accountNo);
    }
}

package id.behavio.qris.platform.core.rule;

/** Operator perbandingan untuk Condition (bagian dari AST). */
public enum CompareOp {
    LT, LTE, GT, GTE, EQ, NEQ;

    /** Terapkan operator atas hasil {@link Comparable#compareTo} (cmp). */
    public boolean test(int cmp) {
        return switch (this) {
            case LT  -> cmp < 0;
            case LTE -> cmp <= 0;
            case GT  -> cmp > 0;
            case GTE -> cmp >= 0;
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
        };
    }
}

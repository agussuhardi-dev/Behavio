package id.behavio.core.rule;

import java.math.BigDecimal;

/**
 * Operand dalam Condition AST. Diselesaikan terhadap {@code EvalContext} saat evaluasi.
 * Bagian dari strategi hybrid AST (lihat design.md §8.1).
 */
public sealed interface Operand
        permits Operand.Field, Operand.AccountBalance, Operand.Num, Operand.Str {

    /** Nilai dari field request (mis. {@code amount}, {@code sourceAccountNo}). */
    record Field(String path) implements Operand {}

    /** Saldo account yang nomornya diambil dari field {@code accountNoField}. */
    record AccountBalance(String accountNoField) implements Operand {}

    /** Literal numerik. */
    record Num(BigDecimal value) implements Operand {}

    /** Literal string. */
    record Str(String value) implements Operand {}
}

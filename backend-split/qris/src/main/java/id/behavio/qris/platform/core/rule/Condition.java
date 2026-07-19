package id.behavio.qris.platform.core.rule;

import java.util.List;

/**
 * Pohon kondisi (AST) — bagian IF dari Rule. Direpresentasikan terstruktur agar
 * dapat dirender jadi builder visual (design.md §8.1). Dievaluasi via Interpreter
 * ({@code ConditionEvaluator}).
 */
public sealed interface Condition
        permits Condition.And, Condition.Or, Condition.Compare, Condition.Always {

    /** Semua anak harus benar. */
    record And(List<Condition> children) implements Condition {}

    /** Minimal satu anak benar. */
    record Or(List<Condition> children) implements Condition {}

    /** Perbandingan dua operand. */
    record Compare(Operand left, CompareOp op, Operand right) implements Condition {}

    /** Selalu benar (mis. penanda rule catch-all / fallback eksplisit). */
    record Always() implements Condition {}
}

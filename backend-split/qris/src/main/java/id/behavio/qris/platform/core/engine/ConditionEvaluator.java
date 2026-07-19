package id.behavio.qris.platform.core.engine;

import id.behavio.qris.platform.core.rule.Condition;
import id.behavio.qris.platform.core.rule.Operand;

import java.math.BigDecimal;

/**
 * Interpreter untuk Condition AST (design.md §8.1). Murni & deterministik.
 *
 * Perbandingan: bila kedua operand numerik → banding {@link BigDecimal}; selain itu
 * banding string (hanya EQ/NEQ yang bermakna untuk non-numerik). Operand yang tak
 * ter-resolve (mis. saldo account tak ada) dianggap tidak memenuhi.
 */
public final class ConditionEvaluator {

    public boolean evaluate(Condition condition, EvalContext ctx) {
        return switch (condition) {
            case Condition.Always ignored -> true;
            case Condition.And and -> and.children().stream().allMatch(c -> evaluate(c, ctx));
            case Condition.Or or -> or.children().stream().anyMatch(c -> evaluate(c, ctx));
            case Condition.Compare cmp -> compare(cmp, ctx);
        };
    }

    private boolean compare(Condition.Compare cmp, EvalContext ctx) {
        Object l = resolve(cmp.left(), ctx);
        Object r = resolve(cmp.right(), ctx);
        if (l == null || r == null) {
            // operand tak ter-resolve → hanya NEQ yang bisa benar bila salah satu null
            return switch (cmp.op()) {
                case NEQ -> !java.util.Objects.equals(l, r);
                case EQ -> java.util.Objects.equals(l, r);
                default -> false;
            };
        }
        BigDecimal ln = asNumber(l);
        BigDecimal rn = asNumber(r);
        if (ln != null && rn != null) {
            return cmp.op().test(ln.compareTo(rn));
        }
        // non-numerik → banding string
        return cmp.op().test(l.toString().compareTo(r.toString()));
    }

    private Object resolve(Operand operand, EvalContext ctx) {
        return switch (operand) {
            case Operand.Num n -> n.value();
            case Operand.Str s -> s.value();
            case Operand.Field f -> ctx.field(f.path());
            case Operand.AccountBalance ab -> {
                Object accNo = ctx.field(ab.accountNoField());
                yield accNo == null ? null : ctx.balanceOf(accNo.toString());
            }
        };
    }

    private BigDecimal asNumber(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o instanceof String s) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}

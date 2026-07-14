package id.behavio.core.product;

import id.behavio.core.engine.SimResponse;
import id.behavio.core.rule.FaultSpec;

/**
 * Jembatan {@link SimResponse.Fault} (bentuk yang keluar dari engine) →
 * {@link FaultSpec} (bentuk yang diterapkan adapter web pasca-commit, design.md §4.2).
 * Dipakai handler agar seluruh operasi memakai satu tipe fault yang sama.
 */
public final class FaultSpecs {

    private FaultSpecs() {}

    /**
     * @return null bila tak ada fault. Point selalu AFTER_ACTIONS: fault yang sampai ke
     *         sini pasti fault fisik pasca-aksi — fault "tolak di depan" (titik A) sudah
     *         diselesaikan engine sebagai respons biasa.
     */
    public static FaultSpec physical(SimResponse.Fault f) {
        return f == null ? null
                : new FaultSpec(FaultSpec.Point.AFTER_ACTIONS, f.delayMillis(), f.drop(), f.corrupt());
    }
}

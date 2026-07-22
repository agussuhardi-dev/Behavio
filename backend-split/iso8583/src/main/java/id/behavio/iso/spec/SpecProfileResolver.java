package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldDictionary;
import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.IsoCodecException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Menggabungkan profil dengan rantai induknya menjadi {@link ResolvedSpec}.
 *
 * <p>Aturan penggabungan (anak menang):
 * <ul>
 *   <li><b>fields</b> — digabung PER-DE. Anak menimpa DE yang sama, DE lain tetap warisan.
 *       Inilah yang membuat profil bank cukup berisi "yang berbeda".</li>
 *   <li><b>transport</b> — dipakai milik anak bila ada; kalau tidak, warisi induk.</li>
 *   <li><b>operations</b> — dipakai milik anak bila tidak kosong; kalau tidak, warisi.
 *       Sengaja diganti utuh, bukan digabung: rute setengah-warisan sulit dinalar dan
 *       mudah menimbulkan tabrakan yang tak terduga.</li>
 * </ul>
 */
public final class SpecProfileResolver {

    private final Function<String, SpecProfile> lookup;

    /** @param lookup pencari profil menurut NAMA (versi terbaru), atau {@code null} bila tak ada. */
    public SpecProfileResolver(Function<String, SpecProfile> lookup) {
        this.lookup = lookup;
    }

    public ResolvedSpec resolve(SpecProfile profile) {
        List<SpecProfile> chain = chainOf(profile);   // [akar, …, anak]

        FieldDictionary dict = null;
        TransportSpec transport = null;
        List<OperationRoute> ops = List.of();

        for (SpecProfile p : chain) {
            dict = (dict == null) ? FieldDictionary.of(p.fields()) : dict.with(p.fields());
            if (p.transport() != null) {
                transport = p.transport();
            }
            if (!p.operations().isEmpty()) {
                ops = p.operations();
            }
        }
        if (transport == null) {
            transport = TransportSpec.defaults();
        }
        return new ResolvedSpec(profile.id(), transport, dict, ops);
    }

    /**
     * Rantai warisan dari akar ke anak. Warisan MELINGKAR ditolak dengan menyebut
     * lintasannya — tanpa ini prosesnya berputar sampai stack habis, dan pesan
     * {@code StackOverflowError} tak memberi tahu profil mana yang salah.
     */
    private List<SpecProfile> chainOf(SpecProfile profile) {
        Deque<SpecProfile> stack = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();

        SpecProfile cur = profile;
        while (cur != null) {
            if (!seen.add(cur.name())) {
                throw new IsoCodecException("Warisan profil melingkar: "
                        + String.join(" → ", seen) + " → " + cur.name());
            }
            stack.push(cur);
            String parent = cur.parent();
            if (parent == null) {
                break;
            }
            SpecProfile next = lookup == null ? null : lookup.apply(parent);
            if (next == null) {
                throw new IsoCodecException("Profil '" + cur.name()
                        + "' mewarisi '" + parent + "', tapi profil itu tidak ditemukan");
            }
            cur = next;
        }
        return new ArrayList<>(stack);
    }
}

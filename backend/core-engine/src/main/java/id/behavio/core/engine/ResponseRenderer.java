package id.behavio.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merender body response dari template: mengganti placeholder {@code {{var}}} dengan
 * nilai dari {@code vars} (field request + hasil: referenceNo, responseCode, dll),
 * lalu menulisnya jadi JSON. Template boleh bersarang (Map/List).
 *
 * <p><b>Pengulangan ({@code @each}).</b> Template ini berbentuk struktur (Map/List), bukan
 * teks, jadi sintaks blok gaya Handlebars ({@code {{#each}}…{{/each}}}) tak punya tempat
 * untuk hidup. Sebagai gantinya: satu elemen List yang memuat kunci {@code "@each"}
 * diperlakukan sebagai <i>template baris</i> dan diulang sebanyak isi koleksi yang
 * disebut nilainya:
 *
 * <pre>{@code
 * "detailData": [ { "@each": "transactions",
 *                   "dateTime": "{{dateTime}}",
 *                   "amount": {"value": "{{amountValue}}", "currency": "{{currency}}"} } ]
 * }</pre>
 *
 * Tiap item koleksi ditumpangkan (overlay) di atas {@code vars}, sehingga field item
 * membayangi var luar — {@code {{amountValue}}} di atas berarti nominal <i>baris itu</i>,
 * bukan nominal request. Bentuk template = bentuk output (tetap array), jadi masih enak
 * dibaca & diedit lewat "Edit Response" di dashboard.
 *
 * <p>Koleksi tak ada / bukan List → array kosong (BUKAN baris berisi string kosong).
 * Itu jujur: "tak ada transaksi" harus terlihat sebagai tak ada, bukan satu baris hampa.
 * Item non-Map bisa dirujuk sebagai {@code {{@this}}}. Template tanpa {@code @each}
 * dirender persis seperti sebelumnya.
 */
public final class ResponseRenderer {

    /** Kunci penanda pengulangan; nilainya = nama var koleksi di {@code vars}. */
    public static final String EACH = "@each";

    /** @return body JSON string hasil render. */
    public String render(Map<String, Object> template, Map<String, Object> vars) {
        Object rendered = renderValue(template, vars);
        return Json.write(rendered);
    }

    /**
     * Nama koleksi yang diminta template lewat {@code @each} — dipakai engine untuk tahu
     * data apa yang perlu diambil dari state. Template yang mendeklarasikan kebutuhannya
     * sendiri membuat engine tak perlu menebak dari path/operasi (yang bisa diganti user).
     */
    public static Set<String> requiredCollections(Object template) {
        Set<String> names = new LinkedHashSet<>();
        collectEach(template, names);
        return names;
    }

    private static void collectEach(Object node, Set<String> out) {
        switch (node) {
            case Map<?, ?> m -> {
                Object each = m.get(EACH);
                if (each instanceof String s && !s.isBlank()) out.add(s.trim());
                for (Object v : m.values()) collectEach(v, out);
            }
            case List<?> l -> { for (Object item : l) collectEach(item, out); }
            default -> { }
        }
    }

    private Object renderValue(Object node, Map<String, Object> vars) {
        return switch (node) {
            case null -> null;
            case String s -> substitute(s, vars);
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (EACH.equals(String.valueOf(e.getKey()))) continue;
                    out.put(String.valueOf(e.getKey()), renderValue(e.getValue(), vars));
                }
                yield out;
            }
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for (Object item : l) {
                    if (item instanceof Map<?, ?> m && m.get(EACH) instanceof String name) {
                        expand(m, name.trim(), vars, out);
                    } else {
                        out.add(renderValue(item, vars));
                    }
                }
                yield out;
            }
            default -> node;
        };
    }

    /** Ulang {@code rowTemplate} sekali per item koleksi {@code name}. */
    private void expand(Map<?, ?> rowTemplate, String name, Map<String, Object> vars, List<Object> out) {
        if (!(vars.get(name) instanceof List<?> items)) {
            return; // koleksi tak ada/kosong → tak ada baris sama sekali
        }
        for (Object item : items) {
            Map<String, Object> scope = new LinkedHashMap<>(vars);
            scope.put("@this", item);
            if (item instanceof Map<?, ?> fields) {
                for (Map.Entry<?, ?> e : fields.entrySet()) {
                    scope.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.add(renderValue(rowTemplate, scope));
        }
    }

    /**
     * Bila string berupa satu placeholder utuh {@code {{key}}}, kembalikan nilai
     * aslinya (mempertahankan tipe non-string). Selain itu substitusi in-place jadi string.
     */
    private Object substitute(String s, Map<String, Object> vars) {
        String trimmed = s.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) == -1) {
            String key = trimmed.substring(2, trimmed.length() - 2).trim();
            return vars.getOrDefault(key, "");
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("{{", i);
            if (open < 0) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, open);
            int close = s.indexOf("}}", open + 2);
            if (close < 0) { sb.append(s, open, s.length()); break; }
            String key = s.substring(open + 2, close).trim();
            Object val = vars.get(key);
            sb.append(val == null ? "" : val.toString());
            i = close + 2;
        }
        return sb.toString();
    }
}

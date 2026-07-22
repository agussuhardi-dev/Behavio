package id.behavio.bank.platform.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pengulangan {@code @each} — dipakai response berbentuk daftar (mis. SNAP service 12
 * transaction-history-list) yang jumlah barisnya baru diketahui saat runtime.
 */
class ResponseRendererEachTest {

    private final ResponseRenderer renderer = new ResponseRenderer();

    private static Map<String, Object> row(String value) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("amountValue", value);
        return r;
    }

    /** Template baris diulang sekali per item, dan field item membayangi var luar. */
    @Test
    @DisplayName("@each mengulang baris & field item membayangi var luar")
    void expandsRowPerItemWithItemFieldsShadowing() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");
        item.put("nominal", "{{amountValue}}");
        item.put("mata_uang", "{{currency}}");

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("detailData", List.of(item));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("currency", "IDR");
        vars.put("amountValue", "TIDAK-BOLEH-MUNCUL"); // var luar harus kalah oleh field item
        vars.put("transactions", List.of(row("100.00"), row("250.00")));

        String json = renderer.render(template, vars);

        assertEquals("{\"detailData\":[{\"nominal\":\"100.00\",\"mata_uang\":\"IDR\"},"
                + "{\"nominal\":\"250.00\",\"mata_uang\":\"IDR\"}]}", json);
        assertTrue(!json.contains("TIDAK-BOLEH-MUNCUL"),
                "field item wajib membayangi var luar bernama sama");
    }

    /**
     * Koleksi kosong → array kosong, BUKAN satu baris berisi string kosong. Ini justru
     * bug yang memicu fitur ini: riwayat kosong dulu dilaporkan sebagai satu transaksi
     * hampa. "Tak ada data" harus terlihat sebagai tak ada.
     */
    @Test
    @DisplayName("koleksi kosong/absen → array kosong, bukan baris hampa")
    void emptyOrMissingCollectionYieldsEmptyArray() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");
        item.put("nominal", "{{amountValue}}");

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("detailData", List.of(item));

        assertEquals("{\"detailData\":[]}",
                renderer.render(template, Map.of("transactions", List.of())));
        assertEquals("{\"detailData\":[]}",
                renderer.render(template, Map.of()), "var absen juga harus jadi array kosong");
    }

    /** Kunci penanda tak boleh ikut bocor ke output. */
    @Test
    @DisplayName("kunci @each tidak muncul di output")
    void eachKeyIsStrippedFromOutput() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");
        item.put("nominal", "{{amountValue}}");

        String json = renderer.render(Map.of("d", List.of(item)),
                Map.of("transactions", List.of(row("9.00"))));

        assertTrue(!json.contains("@each"), "penanda @each bocor ke response: " + json);
    }

    /** Pengulangan harus tembus ke struktur bersarang, bukan cuma level atas. */
    @Test
    @DisplayName("@each bekerja di dalam struktur bersarang")
    void expandsInsideNestedStructures() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("@each", "transactions");
        inner.put("v", "{{amountValue}}");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");
        item.put("sourceOfFunds", List.of(Map.of("amount", Map.of("value", "{{amountValue}}"))));

        String json = renderer.render(Map.of("d", List.of(item)),
                Map.of("transactions", List.of(row("7.00"))));

        assertEquals("{\"d\":[{\"sourceOfFunds\":[{\"amount\":{\"value\":\"7.00\"}}]}]}", json);
    }

    /** Item non-Map (mis. daftar string) dirujuk lewat {@code @this}. */
    @Test
    @DisplayName("item skalar bisa dirujuk sebagai {{@this}}")
    void scalarItemsExposedAsThis() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "codes");
        item.put("kode", "{{@this}}");

        String json = renderer.render(Map.of("d", List.of(item)),
                Map.of("codes", List.of("A", "B")));

        assertEquals("{\"d\":[{\"kode\":\"A\"},{\"kode\":\"B\"}]}", json);
    }

    /** Template lama (tanpa @each) wajib berperilaku persis seperti sebelumnya. */
    @Test
    @DisplayName("template tanpa @each tak berubah perilakunya")
    void templatesWithoutEachAreUnaffected() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("responseCode", "{{responseCode}}");
        template.put("list", List.of("{{a}}", "statis"));

        String json = renderer.render(template, Map.of("responseCode", "2001200", "a", "X"));

        assertEquals("{\"responseCode\":\"2001200\",\"list\":[\"X\",\"statis\"]}", json);
    }

    /** Engine memakai ini untuk tahu koleksi apa yang perlu diambil dari state. */
    @Test
    @DisplayName("requiredCollections menemukan nama koleksi termasuk yang bersarang")
    void requiredCollectionsFindsNestedNames() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@each", "transactions");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("@each", "fees");

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("detailData", List.of(item));
        template.put("wrap", Map.of("inner", List.of(nested)));

        assertEquals(List.of("transactions", "fees"),
                List.copyOf(ResponseRenderer.requiredCollections(template)));
        assertTrue(ResponseRenderer.requiredCollections(Map.of("a", "{{b}}")).isEmpty());
    }
}

package id.behavio.web;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daftar produk yang aktif, dipetakan dari segmen {@code {product}} di Admin API
 * (mis. {@code /api/admin/v1/bank/simulators} vs {@code /api/admin/v1/qris/simulators}).
 *
 * Berkat ini, controller admin cukup SATU set untuk kedua produk: yang berbeda antar
 * produk hanya runtime yang dipilih, bukan kode controller-nya.
 */
@Component
public class ProductRegistry {

    private final Map<String, ProductRuntime> byKey = new LinkedHashMap<>();

    public ProductRegistry(List<ProductRuntime> runtimes) {
        for (ProductRuntime r : runtimes) {
            byKey.put(r.key(), r);
        }
    }

    /** @throws ResponseStatusException 404 bila segmen product di URL tak dikenal */
    public ProductRuntime require(String product) {
        ProductRuntime r = byKey.get(product == null ? "" : product.trim().toLowerCase());
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Produk tak dikenal: '" + product + "'. Tersedia: " + byKey.keySet());
        }
        return r;
    }

    public List<ProductRuntime> all() {
        return List.copyOf(byKey.values());
    }
}

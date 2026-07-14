package id.behavio.persistence;

import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.product.Operation;
import id.behavio.core.product.ProductCatalog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EndpointRegistry} untuk satu schema produk — routing DATA-DRIVEN: path tiap
 * operasi dapat di-custom per-simulator dari dashboard (design.md §2), karena bank
 * berbeda kerap memakai path/versi berbeda untuk operasi yang sama.
 *
 * Katalog default berasal dari {@link ProductCatalog}, jadi registry ini hanya pernah
 * melihat operasi milik produknya sendiri — profil bank tak mungkin lagi meresolusi
 * path QRIS, dan sebaliknya.
 */
public class SchemaEndpointRegistry implements EndpointRegistry {

    private final JdbcClient db;
    private final SchemaTables t;
    private final ProductCatalog catalog;

    public SchemaEndpointRegistry(JdbcClient db, SchemaTables tables, ProductCatalog catalog) {
        this.db = db;
        this.t = tables;
        this.catalog = catalog;
    }

    @Override
    public Optional<String> resolveOperation(UUID simulatorId, String method, String path) {
        Optional<String> found = db.sql("SELECT operation FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND method = ? AND path = ? AND operation IS NOT NULL")
                .param(simulatorId).param(method).param(path)
                .query(String.class).optional();
        if (found.isPresent()) return found;

        // Self-healing: path default katalog yang belum punya baris (profil dibuat sebelum
        // operasi ini ada di katalog) di-provision saat diakses.
        for (Operation op : catalog.operations()) {
            if (!op.method().equalsIgnoreCase(method) || !op.defaultPath().equals(path)) continue;
            Long already = db.sql("SELECT count(*) FROM " + t.endpoints()
                            + " WHERE simulator_id = ? AND operation = ?")
                    .param(simulatorId).param(op.key())
                    .query(Long.class).single();
            if (already == null || already == 0) {
                db.sql("INSERT INTO " + t.endpoints()
                                + " (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                        .param(UUID.randomUUID()).param(simulatorId).param(op.method())
                        .param(op.defaultPath()).param(op.key())
                        .update();
                return Optional.of(op.key());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<EndpointConfig> list(UUID simulatorId) {
        for (Operation op : catalog.operations()) {
            ensureRow(simulatorId, op);
        }
        List<EndpointConfig> result = new ArrayList<>(db.sql("SELECT operation, method, path, "
                        + "COALESCE(headers::text, '') AS h FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND operation IS NOT NULL ORDER BY operation")
                .param(simulatorId)
                .query((rs, n) -> {
                    String opKey = rs.getString("operation");
                    Operation def = catalog.byKey(opKey).orElse(null);
                    return new EndpointConfig(opKey, rs.getString("method"), rs.getString("path"),
                            def == null ? rs.getString("path") : def.defaultPath(),
                            def == null ? opKey : def.label(),
                            rs.getString("h"));
                })
                .list());
        result.addAll(db.sql("SELECT method, path, COALESCE(headers::text, '') AS h FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND operation IS NULL ORDER BY path")
                .param(simulatorId)
                .query((rs, n) -> new EndpointConfig("", rs.getString("method"), rs.getString("path"),
                        rs.getString("path"), "Custom: " + rs.getString("path"), rs.getString("h")))
                .list());
        return result;
    }

    @Override
    public Optional<EndpointDetail> getDetail(UUID simulatorId, String operation) {
        Optional<Operation> def = catalog.byKey(operation);
        if (def.isEmpty()) return Optional.empty();
        return db.sql("SELECT method, path, COALESCE(headers::text, '') AS h FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation)
                .query((rs, n) -> new EndpointDetail(operation, rs.getString("method"), rs.getString("path"),
                        def.get().defaultPath(), def.get().label(), rs.getString("h"), true))
                .optional();
    }

    @Override
    public EndpointDetail addEndpoint(UUID simulatorId, String method, String path, String headers, String label) {
        requirePath(path);
        try {
            db.sql("INSERT INTO " + t.endpoints()
                            + " (id, simulator_id, method, path, headers, operation) VALUES (?, ?, ?, ?, ?::jsonb, NULL)")
                    .param(UUID.randomUUID()).param(simulatorId).param(method.toUpperCase()).param(path)
                    .param(blankToNull(headers))
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Path '" + path + "' sudah dipakai di simulator ini");
        }
        return new EndpointDetail("", method.toUpperCase(), path, path, label != null ? label : path, headers, false);
    }

    @Override
    public void deleteEndpoint(UUID simulatorId, String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Operation tidak boleh kosong");
        }
        db.sql("DELETE FROM " + t.endpoints() + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation).update();
    }

    @Override
    public void updateEndpointMeta(UUID simulatorId, String operation, String method, String headers, String label) {
        int updated = db.sql("UPDATE " + t.endpoints() + " SET method = ?, headers = ?::jsonb "
                        + "WHERE simulator_id = ? AND operation = ?")
                .param(method.toUpperCase()).param(blankToNull(headers)).param(simulatorId).param(operation)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Endpoint tidak ditemukan: " + operation);
        }
    }

    @Override
    public void updatePath(UUID simulatorId, String operation, String newPath) {
        Operation op = require(operation);
        requirePath(newPath);
        try {
            int updated = db.sql("UPDATE " + t.endpoints() + " SET path = ? WHERE simulator_id = ? AND operation = ?")
                    .param(newPath).param(simulatorId).param(operation)
                    .update();
            if (updated == 0) {
                db.sql("INSERT INTO " + t.endpoints()
                                + " (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                        .param(UUID.randomUUID()).param(simulatorId).param(op.method()).param(newPath).param(operation)
                        .update();
            }
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Path '" + newPath + "' sudah dipakai operasi lain di simulator ini");
        }
    }

    @Override
    public void resetPath(UUID simulatorId, String operation) {
        updatePath(simulatorId, operation, require(operation).defaultPath());
    }

    private void ensureRow(UUID simulatorId, Operation op) {
        Long count = db.sql("SELECT count(*) FROM " + t.endpoints() + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(op.key())
                .query(Long.class).single();
        if (count != null && count > 0) return;
        db.sql("INSERT INTO " + t.endpoints() + " (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                .param(UUID.randomUUID()).param(simulatorId).param(op.method()).param(op.defaultPath()).param(op.key())
                .update();
    }

    private Operation require(String operation) {
        return catalog.byKey(operation).orElseThrow(() -> new IllegalArgumentException(
                "Operasi '" + operation + "' bukan milik produk " + catalog.key()));
    }

    private static void requirePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path harus diawali '/'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

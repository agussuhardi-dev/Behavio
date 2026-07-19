package id.behavio.qris.platform.persistence;

import id.behavio.qris.platform.core.port.EndpointRegistry;
import id.behavio.qris.platform.core.product.Operation;
import id.behavio.qris.platform.core.product.ProductCatalog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

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
        return db.sql("SELECT operation, method, path, COALESCE(headers::text, '') AS h FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND operation IS NOT NULL ORDER BY operation")
                .param(simulatorId)
                .query((rs, n) -> {
                    String opKey = rs.getString("operation");
                    Operation def = catalog.byKey(opKey).orElse(null);
                    return new EndpointConfig(opKey, rs.getString("method"), rs.getString("path"),
                            def == null ? rs.getString("path") : def.defaultPath(),
                            def == null ? ("Custom: " + rs.getString("path")) : def.label(),
                            rs.getString("h"));
                })
                .list();
    }

    @Override
    public Optional<EndpointDetail> getDetail(UUID simulatorId, String operation) {
        Optional<Operation> def = catalog.byKey(operation);
        boolean isCatalog = def.isPresent();
        String defaultPath = def.map(Operation::defaultPath).orElse(null);
        String label = def.map(Operation::label).orElse(null);
        return db.sql("SELECT method, path, COALESCE(headers::text, '') AS h FROM " + t.endpoints()
                        + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation)
                .query((rs, n) -> {
                    String path = rs.getString("path");
                    return new EndpointDetail(operation, rs.getString("method"), path,
                            isCatalog ? defaultPath : path,
                            isCatalog ? label : ("Custom: " + path),
                            rs.getString("h"), isCatalog);
                })
                .optional();
    }

    @Override
    public EndpointDetail addEndpoint(UUID simulatorId, String method, String path, String headers, String label) {
        requirePath(path);
        String operationKey = "custom-" + UUID.randomUUID().toString().substring(0, 8);
        UUID endpointId = UUID.randomUUID();
        try {
            db.sql("INSERT INTO " + t.endpoints()
                            + " (id, simulator_id, method, path, headers, operation) VALUES (?, ?, ?, ?, ?::jsonb, ?)")
                    .param(endpointId).param(simulatorId).param(method.toUpperCase()).param(path)
                    .param(blankToNull(headers)).param(operationKey)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Path '" + path + "' sudah dipakai di simulator ini");
        }
        String defaultDef = "{\n  \"fallback\": {\n    \"actions\": [],\n    \"response\": {\n      \"httpStatus\": 200,\n      \"responseCode\": \"2005700\",\n      \"responseMessage\": \"Successful\",\n      \"body\": {}\n    }\n  }\n}";
        UUID scenarioId = UUID.randomUUID();
        db.sql("INSERT INTO " + t.scenarios() + " (id, endpoint_id, name, definition) VALUES (?, ?, ?, ?::jsonb)")
                .param(scenarioId).param(endpointId).param("Normal").param(defaultDef)
                .update();
        db.sql("UPDATE " + t.endpoints() + " SET active_scenario_id = ? WHERE id = ?")
                .param(scenarioId).param(endpointId).update();
        return new EndpointDetail(operationKey, method.toUpperCase(), path, path,
                label != null ? label : path, headers, false);
    }

    @Override
    public void deleteEndpoint(UUID simulatorId, String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Operation tidak boleh kosong");
        }
        int deleted = db.sql("DELETE FROM " + t.endpoints() + " WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation).update();
        if (deleted == 0) {
            throw new IllegalArgumentException("Endpoint tidak ditemukan: " + operation);
        }
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
        requirePath(newPath);
        try {
            int updated = db.sql("UPDATE " + t.endpoints() + " SET path = ? WHERE simulator_id = ? AND operation = ?")
                    .param(newPath).param(simulatorId).param(operation)
                    .update();
            if (updated == 0) {
                Operation op = catalog.byKey(operation).orElse(null);
                if (op == null) {
                    throw new IllegalArgumentException("Endpoint tidak ditemukan: " + operation);
                }
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
        Operation op = catalog.byKey(operation).orElse(null);
        if (op == null) {
            throw new IllegalArgumentException("Reset path hanya untuk operasi katalog: " + operation);
        }
        updatePath(simulatorId, operation, op.defaultPath());
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

    private static void requirePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path harus diawali '/'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

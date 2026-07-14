package id.behavio.persistence;

import id.behavio.core.blueprint.SnapOperations;
import id.behavio.core.port.EndpointRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Implementasi EndpointRegistry via JdbcClient. Mendukung full CRUD:
 * list, get, add (custom), delete, update path, update meta (method/headers/label).
 */
@Repository
public class EndpointRegistryJdbc implements EndpointRegistry {

    private final JdbcClient db;

    public EndpointRegistryJdbc(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<String> resolveOperation(UUID simulatorId, String method, String path) {
        Optional<String> found = db.sql("""
                SELECT operation FROM endpoints
                WHERE simulator_id = ? AND method = ? AND path = ? AND operation IS NOT NULL
                """)
                .param(simulatorId).param(method).param(path)
                .query(String.class).optional();
        if (found.isPresent()) return found;

        for (SnapOperations.Op op : SnapOperations.ALL) {
            if (op.method().equalsIgnoreCase(method) && op.defaultPath().equals(path)) {
                Long already = db.sql("SELECT count(*) FROM endpoints WHERE simulator_id = ? AND operation = ?")
                        .param(simulatorId).param(op.key())
                        .query(Long.class).single();
                if (already == null || already == 0) {
                    db.sql("INSERT INTO endpoints (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                            .param(UUID.randomUUID()).param(simulatorId).param(op.method()).param(op.defaultPath()).param(op.key())
                            .update();
                    return Optional.of(op.key());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<EndpointConfig> list(UUID simulatorId) {
        for (SnapOperations.Op op : SnapOperations.ALL) {
            ensureRow(simulatorId, op);
        }
        var result = new ArrayList<>(db.sql("""
                SELECT operation, method, path, COALESCE(headers::text, '') as h FROM endpoints
                WHERE simulator_id = ? AND operation IS NOT NULL ORDER BY operation
                """)
                .param(simulatorId)
                .query((rs, n) -> {
                    String opKey = rs.getString("operation");
                    SnapOperations.Op def = SnapOperations.byKey(opKey);
                    return new EndpointConfig(opKey, rs.getString("method"), rs.getString("path"),
                            def.defaultPath(), def.label(), rs.getString("h"));
                })
                .list());
        result.addAll(db.sql("""
                SELECT method, path, COALESCE(headers::text, '') as h FROM endpoints
                WHERE simulator_id = ? AND operation IS NULL ORDER BY path
                """)
                .param(simulatorId)
                .query((rs, n) -> new EndpointConfig("", rs.getString("method"),
                        rs.getString("path"), rs.getString("path"), "Custom: " + rs.getString("path"),
                        rs.getString("h")))
                .list());
        return result;
    }

    @Override
    public Optional<EndpointDetail> getDetail(UUID simulatorId, String operation) {
        try {
            SnapOperations.Op def = SnapOperations.byKey(operation);
            return db.sql("""
                    SELECT method, path, COALESCE(headers::text, '') as h FROM endpoints
                    WHERE simulator_id = ? AND operation = ?
                    """)
                    .param(simulatorId).param(operation)
                    .query((rs, n) -> new EndpointDetail(operation, rs.getString("method"),
                            rs.getString("path"), def.defaultPath(), def.label(),
                            rs.getString("h"), true))
                    .optional();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public EndpointDetail addEndpoint(UUID simulatorId, String method, String path,
                                      String headers, String label) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path harus diawali '/'");
        }
        UUID id = UUID.randomUUID();
        try {
            db.sql("INSERT INTO endpoints (id, simulator_id, method, path, headers, operation) VALUES (?, ?, ?, ?, ?::jsonb, NULL)")
                    .param(id).param(simulatorId).param(method.toUpperCase()).param(path)
                    .param(headers == null || headers.isBlank() ? null : headers)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Path '" + path + "' sudah dipakai di simulator ini");
        }
        return new EndpointDetail("", method.toUpperCase(), path, path,
                label != null ? label : path, headers, false);
    }

    @Override
    public void deleteEndpoint(UUID simulatorId, String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Operation tidak boleh kosong");
        }
        db.sql("DELETE FROM endpoints WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(operation)
                .update();
    }

    @Override
    public void updateEndpointMeta(UUID simulatorId, String operation, String method,
                                   String headers, String label) {
        int updated = db.sql("""
                UPDATE endpoints SET method = ?, headers = ?::jsonb
                WHERE simulator_id = ? AND operation = ?
                """)
                .param(method.toUpperCase())
                .param(headers == null || headers.isBlank() ? null : headers)
                .param(simulatorId).param(operation)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Endpoint tidak ditemukan: " + operation);
        }
    }

    private void ensureRow(UUID simulatorId, SnapOperations.Op op) {
        Long count = db.sql("SELECT count(*) FROM endpoints WHERE simulator_id = ? AND operation = ?")
                .param(simulatorId).param(op.key())
                .query(Long.class).single();
        if (count != null && count > 0) return;
        db.sql("INSERT INTO endpoints (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                .param(UUID.randomUUID()).param(simulatorId).param(op.method()).param(op.defaultPath()).param(op.key())
                .update();
    }

    @Override
    public void updatePath(UUID simulatorId, String operation, String newPath) {
        SnapOperations.Op op = SnapOperations.byKey(operation);
        if (newPath == null || newPath.isBlank() || !newPath.startsWith("/")) {
            throw new IllegalArgumentException("Path harus diawali '/'");
        }
        try {
            int updated = db.sql("UPDATE endpoints SET path = ? WHERE simulator_id = ? AND operation = ?")
                    .param(newPath).param(simulatorId).param(operation)
                    .update();
            if (updated == 0) {
                db.sql("INSERT INTO endpoints (id, simulator_id, method, path, operation) VALUES (?, ?, ?, ?, ?)")
                        .param(UUID.randomUUID()).param(simulatorId).param(op.method()).param(newPath).param(operation)
                        .update();
            }
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Path '" + newPath + "' sudah dipakai operasi lain di simulator ini");
        }
    }

    @Override
    public void resetPath(UUID simulatorId, String operation) {
        SnapOperations.Op op = SnapOperations.byKey(operation);
        updatePath(simulatorId, operation, op.defaultPath());
    }
}

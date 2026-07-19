package id.behavio.qris.platform.persistence;

import id.behavio.qris.platform.core.port.EventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * EventPublisher yang menuliskan RequestLog (Recorder) ke schema satu produk. Live View
 * SSE ditangani broadcaster terpisah; keduanya di-compose di app.
 */
public class SchemaRequestLogWriter implements EventPublisher {

    private final JdbcClient db;
    private final SchemaTables t;

    public SchemaRequestLogWriter(JdbcClient db, SchemaTables tables) {
        this.db = db;
        this.t = tables;
    }

    @Override
    public void publishRequestEvent(RequestEvent e) {
        db.sql("INSERT INTO " + t.requestLogs()
                        + " (id, simulator_id, method, path, http_status, response_code, duration_millis) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(UUID.randomUUID()).param(UUID.fromString(e.simulatorId()))
                .param(e.method()).param(e.path())
                .param(e.httpStatus()).param(e.responseCode()).param(e.durationMillis())
                .update();
    }
}

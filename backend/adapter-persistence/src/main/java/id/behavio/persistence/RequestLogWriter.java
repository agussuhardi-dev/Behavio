package id.behavio.persistence;

import id.behavio.core.port.EventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EventPublisher yang menuliskan RequestLog (Recorder). Dipanggil engine di akhir
 * pipeline; berjalan dalam transaksi request simulasi. Live View SSE ditangani
 * broadcaster terpisah (di-compose di app).
 */
@Component
public class RequestLogWriter implements EventPublisher {

    private final JdbcClient db;

    public RequestLogWriter(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void publishRequestEvent(RequestEvent e) {
        db.sql("""
                INSERT INTO request_logs
                  (id, simulator_id, method, path, http_status, response_code, duration_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID()).param(UUID.fromString(e.simulatorId()))
                .param(e.method()).param(e.path())
                .param(e.httpStatus()).param(e.responseCode()).param(e.durationMillis())
                .update();
    }
}

package id.behavio.web;

import id.behavio.core.engine.BehaviorEngine;
import id.behavio.core.engine.SimRequest;
import id.behavio.core.engine.SimResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Menjalankan pipeline engine dalam SATU DB transaction (atomicity langkah aksi —
 * debit + create txn + idempotensi, design.md §4.1). Dipanggil dari server per-port.
 */
@Service
public class SimulationExecutor {

    private final BehaviorEngine engine;

    public SimulationExecutor(BehaviorEngine engine) {
        this.engine = engine;
    }

    @Transactional
    public SimResponse execute(UUID simulatorId, SimRequest request) {
        return engine.handle(simulatorId, request);
    }
}

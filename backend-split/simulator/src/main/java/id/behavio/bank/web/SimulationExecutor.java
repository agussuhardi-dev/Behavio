package id.behavio.bank.web;

import id.behavio.bank.platform.core.engine.BehaviorEngine;
import id.behavio.bank.platform.core.engine.SimRequest;
import id.behavio.bank.platform.core.engine.SimResponse;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Menjalankan pipeline engine dalam SATU DB transaction (atomicity langkah aksi —
 * debit + create txn + idempotensi, design.md §4.1). Dipanggil dari server per-port.
 */
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

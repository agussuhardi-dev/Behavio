package id.behavio.qris.platform.core.engine;

import java.util.UUID;

/**
 * Inbound port / titik masuk Behavior Engine.
 *
 * Implementasi (Fase 1) menjalankan pipeline:
 *   routing → signature → validasi → idempotensi → context → scenario
 *   → rule (first-match) → actions (atomik) → response → log/SSE → webhook
 *
 * Skeleton Fase 0: kontrak saja.
 */
public interface BehaviorEngine {

    SimResponse handle(UUID simulatorId, SimRequest request);
}

package id.behavio.persistence;

import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.domain.Partner;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.rule.Scenario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound adapter: {@link ConfigRepository} berbasis JPA. Fase 1 — Scenario dibangun
 * dari preset {@link TransferIntrabankBlueprint}, dipilih berdasarkan nama scenario aktif
 * di DB (sakelar testing). Parsing rule when/then JSONB arbitrer menyusul (Fase 3).
 */
@Repository
public class JpaConfigRepository implements ConfigRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Partner> findPartner(UUID simulatorId, String partnerHeaderId) {
        return em.createQuery(
                        "select p from PartnerEntity p where p.simulatorId = :sim and p.partnerId = :pid",
                        PartnerEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("pid", partnerHeaderId)
                .getResultList().stream().findFirst()
                .map(e -> new Partner(e.id, e.simulatorId, e.partnerId, e.publicKey, e.clientSecret));
    }

    @Override
    public Optional<Scenario> findActiveScenario(UUID simulatorId, String method, String path) {
        Optional<EndpointEntity> ep = em.createQuery(
                        "select e from EndpointEntity e where e.simulatorId = :sim and e.method = :m and e.path = :p",
                        EndpointEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("m", method)
                .setParameter("p", path)
                .getResultList().stream().findFirst();

        if (ep.isEmpty() || ep.get().activeScenarioId == null) {
            return Optional.empty();
        }
        ScenarioEntity sc = em.find(ScenarioEntity.class, ep.get().activeScenarioId);
        if (sc == null) {
            return Optional.empty();
        }
        return Optional.of(scenarioFromName(sc.name));
    }

    /** Peta nama scenario (DB) → preset Blueprint. Slice pertama: Transfer Intrabank. */
    private Scenario scenarioFromName(String name) {
        return switch (name == null ? "" : name.trim().toLowerCase()) {
            case "saldo kurang" -> TransferIntrabankBlueprint.forcedInsufficient();
            case "limit" -> TransferIntrabankBlueprint.limit();
            default -> TransferIntrabankBlueprint.normal();
        };
    }
}

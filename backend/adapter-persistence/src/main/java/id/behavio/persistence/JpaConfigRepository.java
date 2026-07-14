package id.behavio.persistence;

import id.behavio.core.blueprint.QrisMpmBlueprint;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.rule.Scenario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound adapter: {@link ConfigRepository} berbasis JPA. Scenario aktif = definisi
 * custom (kolom scenarios.definition, dapat di-edit dashboard) bila ada; jika kosong,
 * jatuh ke preset {@link Blueprints} berdasar nama scenario (design.md §2 & §8).
 */
@Repository
public class JpaConfigRepository implements ConfigRepository {

    @PersistenceContext
    private EntityManager em;

    private final JdbcClient db;
    private final ScenarioCodec codec = new ScenarioCodec();

    public JpaConfigRepository(JdbcClient db) {
        this.db = db;
    }

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
        String product = QrisMpmBlueprint.PATH.equals(path) ? "qris" : "transfer";
        return Optional.of(resolveScenario(sc.id, product, sc.name));
    }

    /** Definisi custom (JSON) bila ada, selain itu preset blueprint. */
    private Scenario resolveScenario(UUID scenarioId, String product, String name) {
        Optional<String> custom = db.sql("SELECT COALESCE(definition::text, '') FROM scenarios WHERE id = ?")
                .param(scenarioId)
                .query(String.class).optional();
        if (custom.isPresent() && !custom.get().isBlank()) {
            return codec.parse(name, custom.get());
        }
        return Blueprints.byName(product, name);
    }

    @Override
    public SignatureMode signatureMode(UUID simulatorId) {
        SimulatorEntity s = em.find(SimulatorEntity.class, simulatorId);
        if (s == null || s.signatureMode == null) {
            return SignatureMode.SIMULATED;
        }
        return "STRICT".equalsIgnoreCase(s.signatureMode)
                ? SignatureMode.STRICT : SignatureMode.SIMULATED;
    }
}

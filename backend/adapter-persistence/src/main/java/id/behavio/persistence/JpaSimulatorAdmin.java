package id.behavio.persistence;

import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.port.SimulatorAdmin;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter Admin (konfigurasi) berbasis JPA. */
@Repository
public class JpaSimulatorAdmin implements SimulatorAdmin {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<SimulatorView> list() {
        return em.createQuery("select s from SimulatorEntity s order by s.name", SimulatorEntity.class)
                .getResultList().stream()
                .map(s -> new SimulatorView(s.id, s.name, s.port, s.status))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SimulatorView> find(UUID simulatorId) {
        SimulatorEntity s = em.find(SimulatorEntity.class, simulatorId);
        return s == null ? Optional.empty()
                : Optional.of(new SimulatorView(s.id, s.name, s.port, s.status));
    }

    @Override
    @Transactional
    public void setStatus(UUID simulatorId, String status) {
        SimulatorEntity s = em.find(SimulatorEntity.class, simulatorId);
        if (s != null) {
            s.status = status;
            em.merge(s);
        }
    }

    @Override
    @Transactional
    public void setActiveScenario(UUID simulatorId, String scenarioName) {
        EndpointEntity ep = em.createQuery(
                        "select e from EndpointEntity e where e.simulatorId = :sim and e.method = :m and e.path = :p",
                        EndpointEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("m", TransferIntrabankBlueprint.METHOD)
                .setParameter("p", TransferIntrabankBlueprint.PATH)
                .getResultList().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("endpoint transfer-intrabank tidak ada untuk simulator " + simulatorId));

        ScenarioEntity sc = em.createQuery(
                        "select s from ScenarioEntity s where s.endpointId = :ep and lower(s.name) = :name",
                        ScenarioEntity.class)
                .setParameter("ep", ep.id)
                .setParameter("name", scenarioName.trim().toLowerCase())
                .getResultList().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario '" + scenarioName + "' tidak ada"));

        ep.activeScenarioId = sc.id;
        em.merge(ep);
    }
}

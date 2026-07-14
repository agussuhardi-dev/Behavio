package id.behavio.persistence;

import id.behavio.core.blueprint.QrisMpmBlueprint;
import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.port.SimulatorAdmin;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    public void setActiveScenario(UUID simulatorId, String product, String scenarioName) {
        String method = "qris".equalsIgnoreCase(product) ? QrisMpmBlueprint.METHOD : TransferIntrabankBlueprint.METHOD;
        String path = "qris".equalsIgnoreCase(product) ? QrisMpmBlueprint.PATH : TransferIntrabankBlueprint.PATH;
        EndpointEntity ep = em.createQuery(
                        "select e from EndpointEntity e where e.simulatorId = :sim and e.method = :m and e.path = :p",
                        EndpointEntity.class)
                .setParameter("sim", simulatorId)
                .setParameter("m", method)
                .setParameter("p", path)
                .getResultList().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("endpoint '" + product + "' tidak ada untuk simulator " + simulatorId));

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

    private static final String[] SCENARIO_NAMES = {
            "Normal", "Saldo Kurang", "Limit", "Bank Down", "Timeout",
            "Commit Then Drop", "Malformed", "Async Callback"
    };
    private static final String[] QRIS_SCENARIO_NAMES = {
            "Normal", "Merchant Diblokir", "Service Down"
    };

    @Override
    @Transactional
    public UUID create(String name, int port, String signatureMode) {
        return provisionBaseline(name, port, signatureMode);
    }

    @Override
    @Transactional
    public UUID cloneSimulator(UUID sourceId, String name, int port) {
        SimulatorEntity src = em.find(SimulatorEntity.class, sourceId);
        if (src == null) {
            throw new IllegalArgumentException("Simulator sumber tidak ditemukan");
        }
        UUID newId = provisionBaseline(name, port, src.signatureMode);

        // Salin kunci partner (public key / client secret)
        em.createNativeQuery("""
                UPDATE partners np SET
                  public_key = sp.public_key, client_secret = sp.client_secret
                FROM partners sp
                WHERE np.simulator_id = :dst AND sp.simulator_id = :src
                """)
                .setParameter("dst", newId).setParameter("src", sourceId)
                .executeUpdate();

        // Salin definisi custom scenario (override) berdasar nama
        em.createNativeQuery("""
                UPDATE scenarios ns SET definition = ss.definition
                FROM scenarios ss
                JOIN endpoints se ON ss.endpoint_id = se.id
                JOIN endpoints ne ON ne.simulator_id = :dst AND ne.path = se.path
                WHERE ns.endpoint_id = ne.id AND se.simulator_id = :src AND ss.name = ns.name
                  AND ss.definition IS NOT NULL
                """)
                .setParameter("dst", newId).setParameter("src", sourceId)
                .executeUpdate();

        // Salin akun sumber (ganti akun default)
        em.createNativeQuery("DELETE FROM accounts WHERE simulator_id = :dst")
                .setParameter("dst", newId).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO accounts (id, simulator_id, partner_id, account_no, holder_name, currency, balance)
                SELECT gen_random_uuid(), :dst, (SELECT id FROM partners WHERE simulator_id = :dst LIMIT 1),
                       account_no, holder_name, currency, balance
                FROM accounts WHERE simulator_id = :src
                """)
                .setParameter("dst", newId).setParameter("src", sourceId)
                .executeUpdate();
        return newId;
    }

    @Override
    @Transactional
    public void delete(UUID simulatorId) {
        SimulatorEntity s = em.find(SimulatorEntity.class, simulatorId);
        if (s != null) {
            em.remove(s); // FK ON DELETE CASCADE menghapus config + state
        }
    }

    /** Buat simulator baru dengan baseline SNAP (partner, akun, endpoint, 8 scenario). */
    private UUID provisionBaseline(String name, int port, String signatureMode) {
        UUID simId = UUID.randomUUID();
        SimulatorEntity sim = new SimulatorEntity();
        sim.id = simId;
        sim.name = name;
        sim.port = port;
        sim.status = "STOPPED";
        sim.signatureMode = signatureMode == null ? "SIMULATED" : signatureMode;
        em.persist(sim);

        UUID partnerId = UUID.randomUUID();
        PartnerEntity partner = new PartnerEntity();
        partner.id = partnerId;
        partner.simulatorId = simId;
        partner.partnerId = "PARTNER001";
        partner.clientSecret = "secret123";
        em.persist(partner);

        em.persist(account(simId, partnerId, "1234567890", "Andi Sumber", "1000000.00"));
        em.persist(account(simId, partnerId, "9876543210", "Budi Tujuan", "0.00"));

        UUID endpointId = UUID.randomUUID();
        EndpointEntity ep = new EndpointEntity();
        ep.id = endpointId;
        ep.simulatorId = simId;
        ep.method = TransferIntrabankBlueprint.METHOD;
        ep.path = TransferIntrabankBlueprint.PATH;
        ep.operation = "transfer";
        em.persist(ep);

        UUID normalId = null;
        for (String scName : SCENARIO_NAMES) {
            UUID scId = UUID.randomUUID();
            if ("Normal".equals(scName)) normalId = scId;
            ScenarioEntity sc = new ScenarioEntity();
            sc.id = scId;
            sc.endpointId = endpointId;
            sc.name = scName;
            em.persist(sc);
        }
        ep.activeScenarioId = normalId;
        em.merge(ep);

        // Endpoint QRIS MPM Generate + scenario baseline (design.md Lampiran A3)
        UUID qrisEndpointId = UUID.randomUUID();
        EndpointEntity qrisEp = new EndpointEntity();
        qrisEp.id = qrisEndpointId;
        qrisEp.simulatorId = simId;
        qrisEp.method = QrisMpmBlueprint.METHOD;
        qrisEp.path = QrisMpmBlueprint.PATH;
        qrisEp.operation = "qris-generate";
        em.persist(qrisEp);

        UUID qrisNormalId = null;
        for (String scName : QRIS_SCENARIO_NAMES) {
            UUID scId = UUID.randomUUID();
            if ("Normal".equals(scName)) qrisNormalId = scId;
            ScenarioEntity sc = new ScenarioEntity();
            sc.id = scId;
            sc.endpointId = qrisEndpointId;
            sc.name = scName;
            em.persist(sc);
        }
        qrisEp.activeScenarioId = qrisNormalId;
        em.merge(qrisEp);

        // Operasi SNAP lain (tanpa scenario/rule): access-token, VA CRUD, QRIS query/refund/expire.
        // Path default dari katalog SnapOperations — semuanya dapat di-custom dari dashboard.
        for (id.behavio.core.blueprint.SnapOperations.Op op : id.behavio.core.blueprint.SnapOperations.ALL) {
            if ("transfer".equals(op.key()) || "qris-generate".equals(op.key())) continue; // sudah dibuat di atas
            EndpointEntity plain = new EndpointEntity();
            plain.id = UUID.randomUUID();
            plain.simulatorId = simId;
            plain.method = op.method();
            plain.path = op.defaultPath();
            plain.operation = op.key();
            em.persist(plain);
        }

        return simId;
    }

    private static AccountEntity account(UUID sim, UUID partner, String no, String holder, String balance) {
        AccountEntity a = new AccountEntity();
        a.id = UUID.randomUUID();
        a.simulatorId = sim;
        a.partnerId = partner;
        a.accountNo = no;
        a.holderName = holder;
        a.currency = "IDR";
        a.balance = new BigDecimal(balance);
        return a;
    }
}

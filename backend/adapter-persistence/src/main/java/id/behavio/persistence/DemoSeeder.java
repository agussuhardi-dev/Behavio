package id.behavio.persistence;

import id.behavio.core.blueprint.QrisMpmBlueprint;
import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Seed data demo Fase 1 (idempoten): 1 simulator (STOPPED) + partner + 2 rekening +
 * endpoint transfer-intrabank + 3 scenario (Normal/Saldo Kurang/Limit), aktif = Normal.
 * Memudahkan verifikasi end-to-end: start port → curl transfer.
 */
@Component
@Order(20)
public class DemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(String... args) {
        Long count = em.createQuery("select count(s) from SimulatorEntity s", Long.class).getSingleResult();
        if (count > 0) {
            log.info("[seed] simulator sudah ada ({}), lewati seeding demo", count);
            return;
        }

        UUID simId = UUID.randomUUID();
        SimulatorEntity sim = new SimulatorEntity();
        sim.id = simId;
        sim.name = "Bank Simulasi Demo";
        sim.port = 9001;
        sim.status = "STOPPED";
        sim.signatureMode = "SIMULATED";
        em.persist(sim);

        UUID partnerId = UUID.randomUUID();
        PartnerEntity partner = new PartnerEntity();
        partner.id = partnerId;
        partner.simulatorId = simId;
        partner.partnerId = "PARTNER001";
        partner.clientSecret = "secret123";   // untuk verifikasi HMAC (mode STRICT)
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

        UUID normalId = UUID.randomUUID();
        em.persist(scenario(normalId, endpointId, "Normal"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Saldo Kurang"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Limit"));
        // Scenario fault (Fase 2)
        em.persist(scenario(UUID.randomUUID(), endpointId, "Bank Down"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Timeout"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Commit Then Drop"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Malformed"));
        em.persist(scenario(UUID.randomUUID(), endpointId, "Async Callback"));

        ep.activeScenarioId = normalId;   // aktif default: Normal
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

        UUID qrisNormalId = UUID.randomUUID();
        em.persist(scenario(qrisNormalId, qrisEndpointId, "Normal"));
        em.persist(scenario(UUID.randomUUID(), qrisEndpointId, "Merchant Diblokir"));
        em.persist(scenario(UUID.randomUUID(), qrisEndpointId, "Service Down"));
        qrisEp.activeScenarioId = qrisNormalId;
        em.merge(qrisEp);

        for (id.behavio.core.blueprint.SnapOperations.Op op : id.behavio.core.blueprint.SnapOperations.ALL) {
            if ("transfer".equals(op.key()) || "qris-generate".equals(op.key())) continue;
            EndpointEntity plain = new EndpointEntity();
            plain.id = UUID.randomUUID();
            plain.simulatorId = simId;
            plain.method = op.method();
            plain.path = op.defaultPath();
            plain.operation = op.key();
            em.persist(plain);
        }

        log.info("[seed] demo siap — simulator={} port={} partner=PARTNER001 " +
                "source=1234567890(1.000.000) benef=9876543210(0). Scenario aktif: Normal. " +
                "QRIS endpoint siap (scenario aktif: Normal).", simId, sim.port);
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

    private static ScenarioEntity scenario(UUID id, UUID endpointId, String name) {
        ScenarioEntity s = new ScenarioEntity();
        s.id = id;
        s.endpointId = endpointId;
        s.name = name;
        return s;
    }
}

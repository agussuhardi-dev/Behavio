package id.behavio.qris;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.port.PartnerAdmin;
import id.behavio.core.port.ScenarioConfigPort;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.port.WebhookSender;
import id.behavio.core.product.OperationHandler;
import id.behavio.core.product.ProductCatalog;
import id.behavio.persistence.PortRegistry;
import id.behavio.persistence.SchemaAccessTokenStore;
import id.behavio.persistence.SchemaConfigRepository;
import id.behavio.persistence.SchemaEndpointRegistry;
import id.behavio.persistence.SchemaPartnerAdmin;
import id.behavio.persistence.SchemaProvisioning;
import id.behavio.persistence.SchemaRequestLogWriter;
import id.behavio.persistence.SchemaScenarioConfig;
import id.behavio.persistence.SchemaSimulatorAdmin;
import id.behavio.persistence.SchemaTables;
import id.behavio.qris.persistence.QrisDemoSeeder;
import id.behavio.qris.persistence.QrisRepositoryJdbc;
import id.behavio.qris.port.QrisRepository;
import id.behavio.qris.web.QrisService;
import id.behavio.web.AccessTokenService;
import id.behavio.web.ProductRuntime;
import id.behavio.web.SimulatorServerManager;
import id.behavio.webhook.OutboxWebhookSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perakitan produk QRIS (PJP): mesin generik di-instansiasi dengan schema "qris" +
 * katalog QRIS + handler QRIS (design.md §3.4).
 *
 * Cermin dari {@code BankProductConfig} — sengaja: yang berbeda antara kedua produk
 * hanyalah schema, katalog, dan handler-nya. Semua mesin di antaranya sama persis dan
 * hanya ada satu salinan kodenya.
 */
@Configuration
public class QrisProductConfig {

    static final String SCHEMA = QrisCatalog.KEY;

    private final JdbcClient db;

    public QrisProductConfig(JdbcClient db) {
        this.db = db;
    }

    @Bean
    public SchemaTables qrisTables() {
        return new SchemaTables(SCHEMA);
    }

    @Bean
    public ProductCatalog qrisCatalog() {
        return new QrisCatalog();
    }

    @Bean
    public QrisRepository qrisRepository() {
        return new QrisRepositoryJdbc(db);
    }

    @Bean
    public ConfigRepository qrisConfigRepository() {
        return new SchemaConfigRepository(db, qrisTables(), qrisCatalog());
    }

    @Bean
    public AccessTokenStore qrisAccessTokenStore() {
        return new SchemaAccessTokenStore(db, qrisTables());
    }

    @Bean
    public EndpointRegistry qrisEndpointRegistry() {
        return new SchemaEndpointRegistry(db, qrisTables(), qrisCatalog());
    }

    @Bean
    public PartnerAdmin qrisPartnerAdmin() {
        return new SchemaPartnerAdmin(db, qrisTables());
    }

    @Bean
    public SchemaProvisioning qrisProvisioning() {
        return new SchemaProvisioning(db, qrisTables(), qrisCatalog());
    }

    @Bean
    public ScenarioConfigPort qrisScenarioConfig() {
        return new SchemaScenarioConfig(db, qrisTables(), qrisCatalog(), qrisProvisioning());
    }

    /** Tanpa BaselineExtension: profil QRIS tak punya state baseline (rekening itu milik bank). */
    @Bean
    public SimulatorAdmin qrisSimulatorAdmin(PortRegistry ports) {
        return new SchemaSimulatorAdmin(db, qrisTables(), qrisCatalog(), qrisProvisioning(), ports, null);
    }

    @Bean
    public WebhookSender qrisWebhookSender() {
        return new OutboxWebhookSender(db, SCHEMA);
    }

    @Bean
    public AccessTokenService qrisAccessTokenService(SignatureVerifier verifier) {
        return new AccessTokenService(qrisConfigRepository(), verifier, qrisAccessTokenStore());
    }

    @Bean
    public QrisService qrisService(SignatureVerifier verifier, ObjectMapper mapper) {
        return new QrisService(qrisConfigRepository(), verifier, qrisAccessTokenStore(),
                qrisRepository(), qrisWebhookSender(), mapper);
    }

    @Bean
    public QrisDemoSeeder qrisDemoSeeder(PortRegistry ports) {
        return new QrisDemoSeeder(db, qrisSimulatorAdmin(ports));
    }

    @Bean
    public ProductRuntime qrisRuntime(PortRegistry ports, SignatureVerifier verifier, ObjectMapper mapper,
                                      List<EventPublisher> sharedPublishers) {
        AccessTokenService tokens = qrisAccessTokenService(verifier);
        QrisService qris = qrisService(verifier, mapper);

        Map<String, OperationHandler> handlers = new LinkedHashMap<>();
        handlers.put("access-token", r -> {
            AccessTokenService.Result t = tokens.issue(r.simulatorId(), r.headers(), r.body());
            return new OperationHandler.Result(t.status(), t.body());
        });
        handlers.put("qris-generate", r -> result(qris.generate(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-query", r -> result(qris.query(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-refund", r -> result(qris.refund(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-cancel", r -> result(qris.cancel(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-decode", r -> result(qris.decode(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-payment", r -> result(qris.payment(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("qris-apply-ott", r -> result(qris.applyOtt(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));

        SimulatorServerManager servers = new SimulatorServerManager(
                SCHEMA, qrisEndpointRegistry(), handlers, eventPublishers(sharedPublishers));
        return new ProductRuntime(qrisCatalog(), qrisSimulatorAdmin(ports), qrisScenarioConfig(),
                qrisEndpointRegistry(), qrisPartnerAdmin(), servers, handlers);
    }

    /** Lihat catatan di BankProductConfig: writer log sengaja bukan bean agar tak tercampur produk. */
    private List<EventPublisher> eventPublishers(List<EventPublisher> shared) {
        List<EventPublisher> all = new ArrayList<>(shared);
        all.add(new SchemaRequestLogWriter(db, qrisTables()));
        return all;
    }

    private static OperationHandler.Result result(QrisService.Result r) {
        return new OperationHandler.Result(r.status(), r.body(), r.fault());
    }
}

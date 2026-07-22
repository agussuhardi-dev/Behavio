package id.behavio.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.behavio.bank.engine.DefaultBehaviorEngine;
import id.behavio.bank.persistence.AccountAdminJdbc;
import id.behavio.bank.persistence.BankBaseline;
import id.behavio.bank.persistence.BankDemoSeeder;
import id.behavio.bank.persistence.JpaStateRepository;
import id.behavio.bank.persistence.VirtualAccountRepositoryJdbc;
import id.behavio.bank.port.AccountAdmin;
import id.behavio.bank.port.StateRepository;
import id.behavio.bank.port.VirtualAccountRepository;
import id.behavio.bank.web.SimulationExecutor;
import id.behavio.bank.web.VirtualAccountService;
import id.behavio.bank.platform.core.engine.SimRequest;
import id.behavio.bank.platform.core.engine.SimResponse;
import id.behavio.bank.platform.core.port.AccessTokenStore;
import id.behavio.bank.platform.core.port.ConfigRepository;
import id.behavio.bank.platform.core.port.EndpointRegistry;
import id.behavio.bank.platform.core.port.EventPublisher;
import id.behavio.bank.platform.core.port.PartnerAdmin;
import id.behavio.bank.platform.core.port.ScenarioConfigPort;
import id.behavio.bank.platform.core.port.SignatureVerifier;
import id.behavio.bank.platform.core.port.SimulatorAdmin;
import id.behavio.bank.platform.core.port.WebhookSender;
import id.behavio.bank.platform.core.port.WebhookSubscriptions;
import id.behavio.bank.platform.core.product.FaultSpecs;
import id.behavio.bank.platform.core.product.OperationHandler;
import id.behavio.bank.platform.core.product.ProductCatalog;
import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.engine.ResponseRenderer;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;
import id.behavio.bank.platform.persistence.PortRegistry;
import id.behavio.bank.platform.persistence.SchemaAccessTokenStore;
import id.behavio.bank.platform.persistence.SchemaConfigRepository;
import id.behavio.bank.platform.persistence.SchemaEndpointRegistry;
import id.behavio.bank.platform.persistence.SchemaPartnerAdmin;
import id.behavio.bank.platform.persistence.SchemaProvisioning;
import id.behavio.bank.platform.persistence.SchemaRequestLogWriter;
import id.behavio.bank.platform.persistence.SchemaScenarioConfig;
import id.behavio.bank.platform.persistence.SchemaSimulatorAdmin;
import id.behavio.bank.platform.persistence.SchemaTables;
import id.behavio.bank.platform.persistence.SchemaWebhookSubscriptions;
import id.behavio.bank.platform.web.AccessTokenService;
import id.behavio.bank.platform.web.ProductRuntime;
import id.behavio.bank.platform.web.SimulatorServerManager;
import id.behavio.bank.platform.web.SnapRequestMapper;
import id.behavio.bank.platform.webhook.OutboxWebhookSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Perakitan produk BANK: mesin generik ({@code :adapter-persistence}, {@code :adapter-web})
 * di-instansiasi dengan schema "bank" + katalog bank + handler bank (design.md §3.4).
 *
 * Bean di sini dirakit EKSPLISIT (memanggil @Bean tetangganya langsung), bukan lewat
 * autowire by-type: tipe seperti {@code ConfigRepository} / {@code SimulatorAdmin} kini
 * ada DUA di aplikasi — satu per produk — sehingga by-type tak akan tahu mana yang
 * dimaksud, dan salah pilih berarti profil bank membaca schema QRIS.
 */
@Configuration
public class BankProductConfig {

    static final String SCHEMA = BankCatalog.KEY;

    private final JdbcClient db;

    public BankProductConfig(JdbcClient db) {
        this.db = db;
    }

    @Bean
    public SchemaTables bankTables() {
        return new SchemaTables(SCHEMA);
    }

    @Bean
    public ProductCatalog bankCatalog() {
        return new BankCatalog();
    }

    @Bean
    public StateRepository bankStateRepository() {
        return new JpaStateRepository();
    }

    @Bean
    public VirtualAccountRepository bankVaRepository() {
        return new VirtualAccountRepositoryJdbc(db);
    }

    @Bean
    public AccountAdmin bankAccountAdmin() {
        return new AccountAdminJdbc(db);
    }

    @Bean
    public ConfigRepository bankConfigRepository() {
        return new SchemaConfigRepository(db, bankTables(), bankCatalog());
    }

    @Bean
    public AccessTokenStore bankAccessTokenStore() {
        return new SchemaAccessTokenStore(db, bankTables());
    }

    @Bean
    public EndpointRegistry bankEndpointRegistry() {
        return new SchemaEndpointRegistry(db, bankTables(), bankCatalog());
    }

    @Bean
    public PartnerAdmin bankPartnerAdmin() {
        return new SchemaPartnerAdmin(db, bankTables());
    }

    @Bean
    public SchemaProvisioning bankProvisioning() {
        return new SchemaProvisioning(db, bankTables(), bankCatalog());
    }

    @Bean
    public ScenarioConfigPort bankScenarioConfig() {
        return new SchemaScenarioConfig(db, bankTables(), bankCatalog(), bankProvisioning());
    }

    @Bean
    public SimulatorAdmin bankSimulatorAdmin(PortRegistry ports) {
        return new SchemaSimulatorAdmin(db, bankTables(), bankCatalog(),
                bankProvisioning(), ports, new BankBaseline(db));
    }

    @Bean
    public WebhookSender bankWebhookSender() {
        return new OutboxWebhookSender(db, SCHEMA);
    }

    /** Registrasi URL notifikasi — satu-satunya sumber URL webhook (design.md §9.1). */
    @Bean
    public WebhookSubscriptions bankWebhookSubscriptions() {
        return new SchemaWebhookSubscriptions(db, bankTables());
    }

    @Bean
    public SimulationExecutor bankSimulationExecutor(SignatureVerifier verifier) {
        return new SimulationExecutor(new DefaultBehaviorEngine(
                bankStateRepository(), bankConfigRepository(), verifier,
                bankWebhookSender(), bankWebhookSubscriptions(), bankAccessTokenStore()));
    }

    @Bean
    public AccessTokenService bankAccessTokenService(SignatureVerifier verifier) {
        return new AccessTokenService(bankConfigRepository(), verifier, bankAccessTokenStore());
    }

    @Bean
    public VirtualAccountService bankVirtualAccountService(SignatureVerifier verifier, ObjectMapper mapper) {
        return new VirtualAccountService(bankConfigRepository(), verifier, bankVaRepository(),
                bankWebhookSender(), bankWebhookSubscriptions(), bankAccessTokenStore(), mapper);
    }

    @Bean
    public BankDemoSeeder bankDemoSeeder(PortRegistry ports) {
        return new BankDemoSeeder(db, bankSimulatorAdmin(ports));
    }

    @Bean
    public ProductRuntime bankRuntime(PortRegistry ports, SignatureVerifier verifier, ObjectMapper mapper,
                                      SnapRequestMapper snapMapper, List<EventPublisher> sharedPublishers) {
        ConfigRepository config = bankConfigRepository();
        AccessTokenService tokens = bankAccessTokenService(verifier);
        SimulationExecutor executor = bankSimulationExecutor(verifier);
        VirtualAccountService va = bankVirtualAccountService(verifier, mapper);

        Map<String, OperationHandler> handlers = new LinkedHashMap<>();
        handlers.put("access-token", renderableHandler(config, r -> {
            var t = tokens.issue(r.simulatorId(), r.headers(), r.body());
            return new Renderable(t.status(), t.body(), t.vars());
        }));
        handlers.put("transfer", r -> transfer(executor, snapMapper, r));
        handlers.put("transfer-interbank", r -> transfer(executor, snapMapper, r));
        handlers.put("balance-inquiry", r -> transfer(executor, snapMapper, r));
        handlers.put("account-inquiry-internal", r -> transfer(executor, snapMapper, r));
        handlers.put("account-inquiry-external", r -> transfer(executor, snapMapper, r));
        handlers.put("transaction-history-list", r -> transfer(executor, snapMapper, r));
        handlers.put("va-create", renderableHandler(config, r -> {
            var x = va.create(r.simulatorId(), r.method(), r.path(), r.headers(), r.body());
            return new Renderable(x.status(), x.body(), x.vars());
        }));
        handlers.put("va-status", renderableHandler(config, r -> {
            var x = va.inquiry(r.simulatorId(), r.method(), r.path(), r.headers(), r.body());
            return new Renderable(x.status(), x.body(), x.vars());
        }));
        handlers.put("va-delete", renderableHandler(config, r -> {
            var x = va.delete(r.simulatorId(), r.method(), r.path(), r.headers(), r.body());
            return new Renderable(x.status(), x.body(), x.vars());
        }));

        SimulatorServerManager servers = new SimulatorServerManager(
                SCHEMA, bankEndpointRegistry(), handlers, eventPublishers(sharedPublishers));
        return new ProductRuntime(bankCatalog(), bankSimulatorAdmin(ports), bankScenarioConfig(),
                bankEndpointRegistry(), bankPartnerAdmin(), bankWebhookSubscriptions(), servers, handlers);
    }

    /**
     * Bungkus handler non-engine dengan logika scenario: Bank Down langsung balas 503,
     * Timeout tambah delay 5 detik setelah handler selesai. Scenario Normal dan lainnya
     * (termasuk custom definition) diferuskan ke handler asli.
     */
    /**
     * Hasil handler non-engine yang body-nya boleh dirender ulang dari template scenario.
     * Jembatan seragam untuk {@code VirtualAccountService.Result} & {@code
     * AccessTokenService.Result} — dua record berbeda dengan bentuk yang sama.
     *
     * @param vars {@code null} = hasil final (error), tak boleh dirender ulang.
     */
    private record Renderable(int status, String body, Map<String, Object> vars) {}

    /**
     * Handler endpoint non-engine (VA & access-token): logika tetap di service, tapi
     * <b>body dirender dari template scenario aktif</b> — inilah yang membuat "Edit
     * Response" berlaku di sana.
     *
     * <p>Sebelum 2026-07-19 body-nya dihardcode di service, sehingga definisi custom yang
     * disimpan user tersimpan rapi (<i>status: saved</i>) tapi <b>diam-diam diabaikan</b>
     * saat request datang — persis pola yang §9.1 larang: bisa diatur tapi tak berefek.
     *
     * <p>Error bisnis/auth (VA tak ditemukan, field wajib kosong, signature invalid) TIDAK
     * dirender ulang: template sukses tak boleh menimpa pesan error.
     */
    private static OperationHandler renderableHandler(
            ConfigRepository config,
            Function<OperationHandler.Request, Renderable> call) {
        ResponseRenderer renderer = new ResponseRenderer();
        return r -> {
            Scenario active = config.findActiveScenario(r.simulatorId(), r.method(), r.path()).orElse(null);
            if (active != null && "Bank Down".equalsIgnoreCase(active.name())) {
                return new OperationHandler.Result(503,
                        "{\"responseCode\":\"5030000\",\"responseMessage\":\"Service Unavailable\"}");
            }
            Renderable svc = call.apply(r);
            OperationHandler.Result out = renderFromScenario(renderer, active, svc);
            if (active != null && "Timeout".equalsIgnoreCase(active.name())) {
                return new OperationHandler.Result(out.status(), out.body(), out.headers(),
                        FaultSpec.delayAfter(5000));
            }
            return out;
        };
    }

    /** Render body dari template scenario; jatuh ke body service bila tak memungkinkan. */
    private static OperationHandler.Result renderFromScenario(ResponseRenderer renderer, Scenario active,
                                                              Renderable svc) {
        if (svc.vars() == null || active == null || active.fallback() == null
                || active.fallback().response() == null) {
            return new OperationHandler.Result(svc.status(), svc.body());
        }
        ResponseSpec spec = active.fallback().response();
        Map<String, Object> vars = new HashMap<>(svc.vars());
        vars.put("responseCode", spec.responseCode());
        vars.put("responseMessage", spec.responseMessage());
        return new OperationHandler.Result(spec.httpStatus(), renderer.render(spec.bodyTemplate(), vars));
    }

    private static OperationHandler withScenario(ConfigRepository config, OperationHandler handler) {
        return r -> {
            Scenario active = config.findActiveScenario(r.simulatorId(), r.method(), r.path()).orElse(null);
            if (active != null && "Bank Down".equalsIgnoreCase(active.name())) {
                return new OperationHandler.Result(503,
                        "{\"responseCode\":\"5030000\",\"responseMessage\":\"Service Unavailable\"}");
            }
            OperationHandler.Result result = handler.handle(r);
            if (active != null && "Timeout".equalsIgnoreCase(active.name())) {
                return new OperationHandler.Result(result.status(), result.body(), result.headers(),
                        FaultSpec.delayAfter(5000));
            }
            return result;
        };
    }

    /**
     * Publisher Live View & request_logs untuk produk ini. Writer log DITAMBAHKAN di sini,
     * bukan dijadikan bean: kalau ia bean, {@code List<EventPublisher>} milik QRIS akan
     * ikut mengambilnya dan menulis simulator_id bank ke {@code qris.request_logs} —
     * langsung melanggar FK.
     */
    private List<EventPublisher> eventPublishers(List<EventPublisher> shared) {
        List<EventPublisher> all = new ArrayList<>(shared);
        all.add(new SchemaRequestLogWriter(db, bankTables()));
        return all;
    }

    /** Transfer (intrabank & interbank) berjalan lewat pipeline engine dalam satu transaksi. */
    private static OperationHandler.Result transfer(SimulationExecutor executor, SnapRequestMapper mapper,
                                                    OperationHandler.Request r) {
        SimRequest request = new SimRequest(r.method(), r.path(), r.headers(), mapper.toFields(r.body()), r.body());
        SimResponse response = executor.execute(r.simulatorId(), request);
        return new OperationHandler.Result(response.httpStatus(), response.body(), response.headers(),
                FaultSpecs.physical(response.fault()));
    }

    private static OperationHandler.Result result(AccessTokenService.Result r) {
        return new OperationHandler.Result(r.status(), r.body());
    }

    private static OperationHandler.Result result(VirtualAccountService.Result r) {
        return new OperationHandler.Result(r.status(), r.body());
    }
}

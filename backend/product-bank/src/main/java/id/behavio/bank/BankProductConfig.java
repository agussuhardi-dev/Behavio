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
import id.behavio.bank.web.AccountService;
import id.behavio.bank.web.SimulationExecutor;
import id.behavio.bank.web.TransactionHistoryService;
import id.behavio.bank.web.VirtualAccountService;
import id.behavio.core.engine.SimRequest;
import id.behavio.core.engine.SimResponse;
import id.behavio.core.port.AccessTokenStore;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.port.PartnerAdmin;
import id.behavio.core.port.ScenarioConfigPort;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.port.WebhookSender;
import id.behavio.core.product.FaultSpecs;
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
import id.behavio.web.AccessTokenService;
import id.behavio.web.ProductRuntime;
import id.behavio.web.SimulatorServerManager;
import id.behavio.web.SnapRequestMapper;
import id.behavio.webhook.OutboxWebhookSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Bean
    public SimulationExecutor bankSimulationExecutor(SignatureVerifier verifier) {
        return new SimulationExecutor(new DefaultBehaviorEngine(
                bankStateRepository(), bankConfigRepository(), verifier,
                bankWebhookSender(), bankAccessTokenStore()));
    }

    @Bean
    public AccessTokenService bankAccessTokenService(SignatureVerifier verifier) {
        return new AccessTokenService(bankConfigRepository(), verifier, bankAccessTokenStore());
    }

    @Bean
    public AccountService bankAccountService(SignatureVerifier verifier, ObjectMapper mapper) {
        return new AccountService(bankConfigRepository(), bankStateRepository(), verifier,
                bankAccessTokenStore(), mapper);
    }

    @Bean
    public TransactionHistoryService bankTransactionHistoryService(SignatureVerifier verifier, ObjectMapper mapper) {
        return new TransactionHistoryService(bankConfigRepository(), bankStateRepository(), verifier,
                bankAccessTokenStore(), mapper);
    }

    @Bean
    public VirtualAccountService bankVirtualAccountService(SignatureVerifier verifier, ObjectMapper mapper) {
        return new VirtualAccountService(bankConfigRepository(), verifier, bankVaRepository(),
                bankWebhookSender(), bankAccessTokenStore(), mapper);
    }

    @Bean
    public BankDemoSeeder bankDemoSeeder(PortRegistry ports) {
        return new BankDemoSeeder(db, bankSimulatorAdmin(ports));
    }

    @Bean
    public ProductRuntime bankRuntime(PortRegistry ports, SignatureVerifier verifier, ObjectMapper mapper,
                                      SnapRequestMapper snapMapper, List<EventPublisher> sharedPublishers) {
        AccessTokenService tokens = bankAccessTokenService(verifier);
        SimulationExecutor executor = bankSimulationExecutor(verifier);
        AccountService accounts = bankAccountService(verifier, mapper);
        TransactionHistoryService history = bankTransactionHistoryService(verifier, mapper);
        VirtualAccountService va = bankVirtualAccountService(verifier, mapper);

        Map<String, OperationHandler> handlers = new LinkedHashMap<>();
        handlers.put("access-token", r -> result(tokens.issue(r.simulatorId(), r.headers(), r.body())));
        handlers.put("transfer", r -> transfer(executor, snapMapper, r));
        handlers.put("transfer-interbank", r -> transfer(executor, snapMapper, r));
        handlers.put("balance-inquiry", r -> result(accounts.balanceInquiry(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("account-inquiry-internal", r -> result(accounts.internalInquiry(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("transaction-history-list", r -> result(history.historyList(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("va-create", r -> result(va.create(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("va-status", r -> result(va.inquiry(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));
        handlers.put("va-delete", r -> result(va.delete(
                r.simulatorId(), r.method(), r.path(), r.headers(), r.body())));

        SimulatorServerManager servers = new SimulatorServerManager(
                SCHEMA, bankEndpointRegistry(), handlers, eventPublishers(sharedPublishers));
        return new ProductRuntime(bankCatalog(), bankSimulatorAdmin(ports), bankScenarioConfig(),
                bankEndpointRegistry(), bankPartnerAdmin(), servers, handlers);
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

    private static OperationHandler.Result result(AccountService.Result r) {
        return new OperationHandler.Result(r.status(), r.body());
    }

    private static OperationHandler.Result result(TransactionHistoryService.Result r) {
        return new OperationHandler.Result(r.status(), r.body());
    }

    private static OperationHandler.Result result(VirtualAccountService.Result r) {
        return new OperationHandler.Result(r.status(), r.body());
    }
}

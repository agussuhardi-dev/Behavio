package id.behavio.web;

import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.PartnerAdmin;
import id.behavio.core.port.ScenarioConfigPort;
import id.behavio.core.port.SimulatorAdmin;
import id.behavio.core.port.WebhookSubscriptions;
import id.behavio.core.product.OperationHandler;
import id.behavio.core.product.ProductCatalog;

import java.util.Map;

/**
 * Satu produk simulator yang siap dilayani: katalog + port admin + server per-port +
 * handler tiap operasi. Dirakit sekali per produk di {@code app} (design.md §3.4).
 *
 * Admin controller generik menerima {@code {product}} dari URL lalu mengambil runtime
 * yang sesuai dari {@link ProductRegistry} — satu set controller melayani bank & QRIS,
 * bukan dua salinan.
 */
public record ProductRuntime(
        ProductCatalog catalog,
        SimulatorAdmin admin,
        ScenarioConfigPort scenarios,
        EndpointRegistry endpoints,
        PartnerAdmin partners,
        WebhookSubscriptions webhooks,
        SimulatorServerManager servers,
        Map<String, OperationHandler> handlers
) {
    public String key() {
        return catalog.key();
    }
}

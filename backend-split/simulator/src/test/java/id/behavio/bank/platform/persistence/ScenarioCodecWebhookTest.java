package id.behavio.bank.platform.persistence;

import id.behavio.bank.platform.core.product.ActionCodec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;
import id.behavio.bank.platform.core.rule.WebhookSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioCodec} × webhook setelah {@code X-CALLBACK-URL} dibuang (design.md §9.1).
 *
 * Yang dijaga di sini bukan sekadar format baru, tapi **definisi scenario milik user yang
 * sudah tersimpan di DB**: definisi itu ditulis tangan lewat editor dashboard dan tak
 * punya cadangan. Kalau codec menolaknya setelah mesin berubah, user kehilangan editannya
 * tanpa cara memulihkan — jadi kompatibilitas mundur di sini bukan kenyamanan, tapi syarat.
 */
class ScenarioCodecWebhookTest {

    private final ScenarioCodec codec = new ScenarioCodec(ActionCodec.NONE);

    /** Definisi ERA LAMA — persis bentuk yang masih ada di scenarios.definition milik user. */
    private static final String DEFINISI_LAMA = """
            {
              "rules": [],
              "fallback": {
                "actions": [],
                "response": {"httpStatus": 200, "responseCode": "2001700",
                             "responseMessage": "Successful", "body": {}},
                "webhook": {"urlHeader": "X-CALLBACK-URL", "delayMillis": 2000,
                            "bodyTemplate": {"latestTransactionStatus": "00"}}
              }
            }""";

    private static final String DEFINISI_BARU = """
            {
              "rules": [],
              "fallback": {
                "actions": [],
                "response": {"httpStatus": 200, "responseCode": "2001700",
                             "responseMessage": "Successful", "body": {}},
                "webhook": {"event": "transfer-notify", "delayMillis": 2000,
                            "bodyTemplate": {"latestTransactionStatus": "00"}}
              }
            }""";

    @Test
    void definisiLamaBerUrlHeaderTetapDimuatDanJatuhKeEventAll() {
        Scenario s = codec.parse("Async Callback", DEFINISI_LAMA);

        WebhookSpec webhook = s.fallback().webhook();
        assertThat(webhook).isNotNull();
        // urlHeader diabaikan; tanpa event → registrasi umum partner.
        assertThat(webhook.event()).isEqualTo(WebhookSpec.EVENT_ALL);
        assertThat(webhook.delayMillis()).isEqualTo(2000);
        assertThat(webhook.bodyTemplate()).containsEntry("latestTransactionStatus", "00");
    }

    @Test
    void definisiBaruMempertahankanEvent() {
        Scenario s = codec.parse("Async Callback", DEFINISI_BARU);
        assertThat(s.fallback().webhook().event()).isEqualTo("transfer-notify");
    }

    @Test
    void roundTripEventPresisi() {
        Scenario asli = new Scenario("Async Callback", List.of(),
                Outcome.withWebhook(List.of(),
                        new ResponseSpec(200, "2001700", "Successful", Map.of()),
                        new WebhookSpec("va-payment", 1500, Map.of("status", "{{x}}"))));

        Scenario ulang = codec.parse("Async Callback", codec.serialize(asli));

        assertThat(ulang.fallback().webhook().event()).isEqualTo("va-payment");
        assertThat(ulang.fallback().webhook().delayMillis()).isEqualTo(1500);
        assertThat(ulang.fallback().webhook().bodyTemplate()).containsEntry("status", "{{x}}");
    }

    /** Serialisasi tak boleh lagi menulis urlHeader — itu kosakata yang sudah mati. */
    @Test
    void serialisasiTidakMenulisUrlHeaderLagi() {
        Scenario s = codec.parse("Async Callback", DEFINISI_LAMA);
        String json = codec.serialize(s);

        assertThat(json).doesNotContain("urlHeader", "X-CALLBACK-URL");
        assertThat(json).contains("\"event\"");
    }

    @Test
    void eventKosongDianggapAll() {
        assertThat(new WebhookSpec(null, 0, Map.of()).event()).isEqualTo(WebhookSpec.EVENT_ALL);
        assertThat(new WebhookSpec("  ", 0, Map.of()).event()).isEqualTo(WebhookSpec.EVENT_ALL);
        assertThat(new WebhookSpec("  va-payment ", 0, Map.of()).event()).isEqualTo("va-payment");
    }

    @Test
    void scenarioTanpaWebhookTetapNull() {
        Scenario s = codec.parse("Normal", """
                {"rules": [], "fallback": {"actions": [],
                 "response": {"httpStatus": 200, "responseCode": "2001700",
                              "responseMessage": "Successful", "body": {}}}}""");
        assertThat(s.fallback().webhook()).isNull();
    }
}

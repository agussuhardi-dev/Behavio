package id.behavio.bank.blueprint;

import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint untuk endpoint Virtual Account — Create.
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class VirtualAccountCreateBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transfer-va/create-va";
    public static final String RC_SUCCESS = "2002700";
    public static final String RC_SERVICE_DOWN = "5030000";

    private VirtualAccountCreateBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A2.2).
     * {@code partnerServiceId} 8 digit rata kanan (left-pad spasi) dan
     * {@code virtualAccountNo} = partnerServiceId + customerNo — bentuk itu bagian dari
     * spec, bukan kebetulan format.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> totalAmount = new LinkedHashMap<>();
        totalAmount.put("value", "100000.00");
        totalAmount.put("currency", "IDR");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerServiceId", "  088899");
        body.put("customerNo", "12345678901234567890");
        body.put("virtualAccountNo", "  08889912345678901234567890");
        body.put("virtualAccountName", "Joko Pelanggan");
        body.put("virtualAccountEmail", "joko@example.com");
        body.put("virtualAccountPhone", "628123456789");
        body.put("totalAmount", totalAmount);
        body.put("virtualAccountTrxType", "C");
        body.put("expiredDate", "2026-07-16T10:00:00+07:00");
        body.put("trxId", "INV-2026-0715-001");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerServiceId", "{{partnerServiceId}}");
        data.put("customerNo", "{{customerNo}}");
        data.put("virtualAccountNo", "{{virtualAccountNo}}");
        data.put("virtualAccountName", "{{virtualAccountName}}");
        data.put("totalAmount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        data.put("virtualAccountStatus", "ACTIVE");
        data.put("trxId", "{{trxId}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("virtualAccountData", data);
        return new Scenario("Normal", List.of(),
                Outcome.of(new ResponseSpec(200, RC_SUCCESS, "Successful", body)));
    }

    public static Scenario bankDown() {
        return new Scenario("Bank Down", List.of(),
                Outcome.of(errorResponse(503, RC_SERVICE_DOWN, "Service Unavailable")));
    }

    public static Scenario timeout() {
        return timeout(5000);
    }

    public static Scenario timeout(long delayMillis) {
        return new Scenario("Timeout", List.of(),
                Outcome.withFault(List.of(),
                        new ResponseSpec(200, RC_SUCCESS, "Successful", normalBody()),
                        FaultSpec.delayAfter(delayMillis)));
    }

    private static Map<String, Object> normalBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerServiceId", "{{partnerServiceId}}");
        data.put("customerNo", "{{customerNo}}");
        data.put("virtualAccountNo", "{{virtualAccountNo}}");
        data.put("virtualAccountName", "{{virtualAccountName}}");
        data.put("totalAmount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        data.put("virtualAccountStatus", "ACTIVE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("virtualAccountData", data);
        return body;
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}
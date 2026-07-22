package id.behavio.bank.blueprint;

import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint untuk endpoint Virtual Account — Delete.
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class VirtualAccountDeleteBlueprint {

    public static final String METHOD = "DELETE";
    public static final String PATH = "/v1.0/transfer-va/delete-va";
    /**
     * Delete VA = ASPI service <b>31</b> → {@code 2003100}. Sebelum 2026-07-19 nilainya
     * {@code 2002500}, yaitu kode service 25 (VA <i>Payment</i>) — service yang sama sekali
     * berbeda. Diverifikasi dari tabel service ASPI (grup Virtual Account).
     */
    public static final String RC_SUCCESS = "2003100";
    public static final String RC_SERVICE_DOWN = "5030000";

    private VirtualAccountDeleteBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A2.1 service 31).
     * Method-nya DELETE tapi SNAP tetap mengirim body — itu memang bentuk spec-nya.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerServiceId", "  088899");
        body.put("customerNo", "12345678901234567890");
        body.put("virtualAccountNo", "  08889912345678901234567890");
        body.put("trxId", "INV-2026-0715-001");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        return new Scenario("Normal", List.of(),
                Outcome.of(new ResponseSpec(200, RC_SUCCESS, "Successful", successBody())));
    }

    /**
     * ASPI service 31 menandai {@code virtualAccountData} <b>Mandatory</b> (dengan
     * partnerServiceId/customerNo/virtualAccountNo Mandatory di dalamnya) — dulu response
     * ini hanya berisi responseCode+responseMessage.
     */
    private static Map<String, Object> successBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerServiceId", "{{partnerServiceId}}");
        data.put("customerNo", "{{customerNo}}");
        data.put("virtualAccountNo", "{{virtualAccountNo}}");
        data.put("trxId", "{{trxId}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("virtualAccountData", data);
        return body;
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
                        new ResponseSpec(200, RC_SUCCESS, "Successful", successBody()),
                        FaultSpec.delayAfter(delayMillis)));
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}
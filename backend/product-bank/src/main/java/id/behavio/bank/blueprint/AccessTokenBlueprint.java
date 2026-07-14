package id.behavio.bank.blueprint;

import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint untuk endpoint Access Token B2B.
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class AccessTokenBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/access-token/b2b";
    public static final String RC_SUCCESS = "2007300";
    public static final String RC_SERVICE_DOWN = "5030000";

    private AccessTokenBlueprint() {}

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("accessToken", "{{accessToken}}");
        body.put("tokenType", "Bearer");
        body.put("expiresIn", "900");
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("accessToken", "{{accessToken}}");
        body.put("tokenType", "Bearer");
        body.put("expiresIn", "900");
        return body;
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}
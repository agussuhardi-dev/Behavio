package id.behavio.bank.blueprint;

import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk **External Account Inquiry** (service 16) — cek nama pemilik
 * rekening di BANK LAIN sebelum {@code transfer-interbank}, pasangan yang selama ini
 * hilang (intrabank punya service 15, interbank tak punya apa-apa).
 *
 * <p>Terverifikasi ke portal ASPI 2026-07-15 (design.md Lampiran A.6): path
 * {@code /v1.0/account-inquiry-external}, sukses {@code 2001600}. Beda dari service 15:
 * request WAJIB membawa {@code beneficiaryBankCode}, dan response membawa
 * {@code beneficiaryBankCode}/{@code beneficiaryBankName} — **tanpa**
 * {@code beneficiaryAccountStatus} maupun {@code beneficiaryAccountType}.
 *
 * <p><b>Kenapa nama pemiliknya literal, bukan {@code {{holderName}}}:</b> rekening yang
 * ditanya ada di bank LAIN, jadi ia memang tak ada di state simulator ini — enrichment
 * dari {@code bank.accounts} akan selalu gagal dan merender string kosong untuk field
 * yang WAJIB. Nilai di sini preset yang **di-override per-simulator** dari dashboard
 * (design.md §2), atau dipilih lewat rule per {@code beneficiaryAccountNo}. Menyimpan
 * rekening bank lain ke `bank.accounts` demi ini akan mengaburkan arti tabel itu.
 */
public final class ExternalAccountInquiryBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/account-inquiry-external";
    public static final String RC_SUCCESS = "2001600";
    public static final String RC_SERVICE_DOWN = "5030000";

    private ExternalAccountInquiryBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5). Sengaja memakai rekening &
     * kode bank yang SAMA dengan contoh {@code transfer-interbank}, sehingga di Postman
     * keduanya membentuk satu alur utuh: inquiry nama dulu → baru transfer.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000007");
        body.put("beneficiaryBankCode", "014");
        body.put("beneficiaryAccountNo", "8877665544");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), normalResponse()));
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
                Outcome.withFault(List.of(), normalResponse(), FaultSpec.delayAfter(delayMillis)));
    }

    private static ResponseSpec normalResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("beneficiaryAccountName", "Citra Penerima");
        body.put("beneficiaryAccountNo", "{{beneficiaryAccountNo}}");
        body.put("beneficiaryBankCode", "{{beneficiaryBankCode}}");
        body.put("beneficiaryBankName", "Bank Penerima");
        body.put("currency", "{{currency}}");
        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}

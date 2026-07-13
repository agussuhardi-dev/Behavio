package id.behavio.core.rule;

import java.util.Map;

/**
 * Cetakan response (bagian THEN). {@code bodyTemplate} boleh berisi placeholder
 * {@code {{var}}} yang dirender dari field request + variabel hasil (referenceNo, dll).
 * Nilai boleh bersarang (Map/List) untuk struktur SNAP seperti {@code amount{value,currency}}.
 */
public record ResponseSpec(
        int httpStatus,
        String responseCode,
        String responseMessage,
        Map<String, Object> bodyTemplate
) {}

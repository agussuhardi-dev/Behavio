package id.behavio.core.rule;

import java.util.Map;

/**
 * Spesifikasi webhook/callback async (design.md §9). URL diambil dari header request
 * ({@code urlHeader}, mis. X-CALLBACK-URL) agar bisa diuji tanpa registrasi. Body
 * dirender dari template (placeholder {{var}}), dikirim setelah {@code delayMillis}.
 */
public record WebhookSpec(
        String urlHeader,
        long delayMillis,
        Map<String, Object> bodyTemplate
) {}

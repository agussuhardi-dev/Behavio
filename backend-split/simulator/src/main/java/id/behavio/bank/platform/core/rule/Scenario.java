package id.behavio.bank.platform.core.rule;

import java.util.List;

/**
 * Kumpulan Rule berurutan (FIRST-MATCH) + fallback bila tak ada yang cocok
 * (design.md §8). Scenario aktif per-endpoint = "sakelar utama testing".
 */
public record Scenario(
        String name,
        List<Rule> rules,
        Outcome fallback
) {}

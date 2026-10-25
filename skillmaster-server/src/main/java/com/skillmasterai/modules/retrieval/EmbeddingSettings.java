package com.skillmasterai.modules.retrieval;

import java.time.Duration;

/**
 * How M13 is configured, as a value rather than a set of keys read in place.
 *
 * <p>The same arrangement as {@code RelevanceWeights}: the module's wiring may not depend on
 * {@code config}, so a class in that package builds this and passes it in. It validates itself,
 * which is what turns a half-filled configuration into a startup failure instead of a silent
 * fallback to keyword search nobody notices for a month.
 *
 * <p><strong>{@link #enabled()} is a privacy switch, not a feature flag.</strong> Turning it off
 * must leave a working product behind — search answers by keyword and nothing tries to reach the
 * provider (v19 PRD #8, test-plan T08). The schema is not part of that promise: pgvector and the
 * table are required by the migration either way.</p>
 *
 * @param apiKey        the provider's key; blank only when disabled
 * @param model         the provider's model name, stored beside every vector
 * @param dimensions    the vector width; it is the column's declared width too
 * @param timeout       one HTTP call's budget; the request path degrades when it expires
 * @param threshold     the lowest cosine similarity a result may have and still be returned
 * @param overFetch     how many candidates per requested row to fetch when the admissible set
 *                      cannot be enumerated (the public plane)
 */
public record EmbeddingSettings(boolean enabled, String apiKey, String model, int dimensions,
        Duration timeout, double threshold, int overFetch) {

    /** The declared width of {@code skill_embedding.embedding}; see V30. */
    public static final int COLUMN_WIDTH = 1024;

    public EmbeddingSettings {
        if (dimensions != COLUMN_WIDTH) {
            // The column is `vector(1024)`. Anything else starts cleanly and then fails on the
            // first publish — at CAST, inside a transaction that rolls back — so it is refused
            // here instead, where the message can say why.
            throw new IllegalArgumentException("embedding dimensions must be " + COLUMN_WIDTH
                    + ", the width of the column; changing it is a migration plus a re-embed");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("embedding timeout must be positive");
        }
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("embedding threshold must be within 0 and 1");
        }
        if (overFetch < 1) {
            throw new IllegalArgumentException("embedding over-fetch must be at least 1");
        }
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            // A key is required exactly when the switch is on. Refusing here is what keeps the
            // disabled case honest: nothing downstream has to ask "enabled but unusable?".
            throw new IllegalArgumentException(
                    "skillmaster.retrieval.api-key is required when retrieval is enabled");
        }
    }
}

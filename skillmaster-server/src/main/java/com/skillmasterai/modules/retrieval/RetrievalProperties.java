package com.skillmasterai.modules.retrieval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code skillmaster.retrieval} — M13's dials, bound where they are used.
 *
 * <p><strong>Every value has a default, including "off".</strong> A deployment that says nothing
 * gets keyword search and no provider traffic, which is the safe direction for a privacy switch;
 * turning it on is an explicit act with a key attached. That is why this record is in the module
 * rather than in {@code config}: an absent block is a valid configuration here, and a required
 * nested record in {@link com.skillmasterai.config.SkillmasterProperties} would have turned it
 * into a startup failure on every existing deployment.
 *
 * @param enabled    whether semantic retrieval is on at all; off means no client, no calls, and
 *                   keyword search everywhere
 * @param apiKey     the provider's key; required only when enabled
 * @param model      the provider's model name
 * @param dimensions the vector width, and the declared width of the column
 * @param timeout    one provider call's budget
 * @param threshold  the lowest cosine similarity a result may have
 * @param overFetch  candidates fetched per requested row when the admissible set cannot be
 *                   enumerated
 */
@ConfigurationProperties("skillmaster.retrieval")
public record RetrievalProperties(Boolean enabled, String apiKey, String model, Integer dimensions,
        Duration timeout, Double threshold, Integer overFetch) {

    public RetrievalProperties {
        enabled = enabled != null && enabled;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null || model.isBlank() ? "text-embedding-v4" : model;
        dimensions = dimensions == null ? 1024 : dimensions;
        // Five seconds, the same as `application.yml`: this call is made inside the publishing
        // transaction, so it is also how long a publish can hold its row lock.
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        // Measured, not guessed (v19 P3): on the checked-in labelled set the best score any skill
        // reached for a query nothing answers is 0.397, and the 5th percentile of the correct
        // answers **that came back in the top 10** is 0.415 — the ones a cut can actually take
        // away. 0.40 is the lowest cut that lets no such query through; above it the number of
        // correct rows lost roughly doubles every two hundredths. Keep in step with
        // `application.yml`, which supplies the value in every real deployment.
        threshold = threshold == null ? 0.40 : threshold;
        overFetch = overFetch == null ? 20 : overFetch;
    }
}

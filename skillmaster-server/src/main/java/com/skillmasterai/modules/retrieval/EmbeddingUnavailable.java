package com.skillmasterai.modules.retrieval;

/**
 * The embedding provider could not be reached, refused, or answered nonsense.
 *
 * <p><strong>Thrown out of the publishing transaction, on purpose</strong> (v19, decided
 * 2026-10-25). The vector is computed inside that transaction, so a publish either commits with
 * its vector or does not happen at all. This type exists so the transport layer can tell that
 * failure apart from a bug and answer 503 with a reason, rather than a bare 500.
 *
 * <p>The message never carries the text being embedded: it is the author's own description.
 */
public class EmbeddingUnavailable extends RuntimeException {

    public EmbeddingUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}

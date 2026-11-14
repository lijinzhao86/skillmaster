package com.skillmasterai.usecase.label;

/**
 * The address names a namespace that may not hold public skills.
 *
 * <p>The inner plane labels only what readers can reach (ADR 0146, ADR 0196); a request for any
 * other namespace is a caller bug and answers 403 rather than quietly returning nothing.
 */
public final class NotOfficialNamespaceException extends RuntimeException {

    private final String namespace;

    public NotOfficialNamespaceException(String namespace) {
        super("labels are only written in the official namespace, not '" + namespace + "'");
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }
}

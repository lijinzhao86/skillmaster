package com.skillmasterai.modules.secret;

import java.util.List;

/**
 * The one response that carries plaintext: the environment a run should receive, and when this
 * answer stops being valid.
 *
 * <p>Five minutes is enough for one run to start and for its child processes to inherit the
 * environment. There is no renewal endpoint: a run that outlives its lease starts a new one.
 *
 * @param values    one entry per effective {@code (env, value)}; own bindings beat shared ones
 * @param expiresAt RFC3339 UTC
 */
public record CredentialLease(List<Value> values, String expiresAt) {

    public CredentialLease {
        values = List.copyOf(values);
    }

    /** One environment variable and the plaintext to put in it. Never logged. */
    public record Value(String env, String value) {
    }

    public static CredentialLease empty(String expiresAt) {
        return new CredentialLease(List.of(), expiresAt);
    }
}

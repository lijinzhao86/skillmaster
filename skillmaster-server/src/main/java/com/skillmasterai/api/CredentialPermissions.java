package com.skillmasterai.api;

import java.util.List;
import java.util.Set;

/**
 * What the credential in hand may actually do: the rung's actions, intersected with the scopes the
 * key carries (v23).
 *
 * <p><strong>The same intersection {@code can_write} has always been.</strong> The rung is the
 * account's, the scope is the key's, and a caller is told what the two allow together — a key issued
 * with {@code skills:read} alone cannot write even when its account owns the skill. {@code
 * permissions} is that fact spelled as a list, so it is computed the same way. Without it a
 * read-only key reads {@code write, discard, share} in the header and is refused by the scope gate on
 * the first attempt, which is exactly the trial-and-round-trip v23 exists to remove.
 *
 * <p>Reads are never filtered: {@code skills:read} is what let the request reach this point, since
 * the gate decides by method ({@link com.skillmasterai.modules.auth.Scopes#requiredForMethod}), and a
 * write scope implies the read one (ADR 0039 §决定 2).
 */
final class CredentialPermissions {

    /**
     * The actions reachable only through a non-GET route, which is where {@code skills:write} is
     * enforced. Spelled out rather than derived from the rung so that a new read-only action cannot
     * be dropped by accident.
     */
    private static final Set<String> WRITE_ACTIONS = Set.of("write", "discard", "share");

    private CredentialPermissions() {
    }

    /**
     * @param credentialMayWrite whether the key carries {@code skills:write}; a browser session
     *                           carries no scopes and is not a credential — it reaches these
     *                           responses through the console, which never asks for this field
     */
    static List<String> of(List<String> permissions, boolean credentialMayWrite) {
        if (credentialMayWrite) {
            return permissions;
        }
        return permissions.stream()
                .filter(permission -> !WRITE_ACTIONS.contains(permission))
                .toList();
    }
}

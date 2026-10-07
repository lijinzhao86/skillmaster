package com.skillmasterai.modules.account;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * M1's public face, as far as P0 needs one.
 *
 * <p>P0 does not implement M1 — registration, login and credentials are P1. This is the minimum
 * M4 needs in order to answer "which namespace is this person's", and nothing else: the personal
 * namespace's slug equals its owner's handle (§3.2), so resolving it requires reading
 * {@code app_user.handle}, which is M1's table and may only be read through M1.
 *
 * <p>Deliberately not a general account service. Every method added here is a column of
 * {@code app_user} that another module is now coupled to, and the point of §2.5 rule 1 is that
 * there should be as few of those as the work actually requires.
 */
public interface AccountDirectory {

    /**
     * @return the user's handle, or empty if no such user exists
     */
    Optional<String> handleOf(String userId);

    /**
     * The account behind a username — the direction sharing travels in (ADR 0034).
     *
     * <p>A grant is made by typing a handle, because that is the only thing about an account a
     * person knows or can say out loud; it is stored as the id, because that is what survives a
     * rename. This is the one step in between.
     *
     * @return the user's id, or empty when no account has that handle
     */
    Optional<String> userIdOf(String handle);

    /**
     * Handles for several users at once.
     *
     * <p>A listing of grants is the only caller, and it is why this exists rather than a loop over
     * {@link #handleOf}: the number of round trips would then be the number of people a skill has
     * been shared with, which is exactly the number that grows.
     *
     * @return one entry per id that names an existing account; a missing id is simply absent, because
     *         a caller rendering a list has nothing better to do with a gap than leave it out
     */
    Map<String, String> handlesOf(Collection<String> userIds);
}

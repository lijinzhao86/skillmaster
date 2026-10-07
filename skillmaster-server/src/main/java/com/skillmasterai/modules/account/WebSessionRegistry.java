package com.skillmasterai.modules.account;

/**
 * The one thing M1 does with sessions it did not create.
 *
 * <p>Sessions belong to Spring Session, which means no code outside the framework may name its
 * tables in SQL. What M1 needs of them is a single operation — end every one belonging to a user —
 * and this is that operation as a method, so that "reset the password" can mean "and nobody stays
 * logged in with the old one" without anyone writing a query against a table this module does not
 * read.
 *
 * <p>Until M2 exists this is the only revocation there is. When tokens arrive, resetting a password
 * has to end those too, and that will be a second call from the same use case rather than a change
 * here — the reason the revocation lives in the use case at all.
 */
public interface WebSessionRegistry {

    /** Ends every browser session belonging to this user. A no-op when there are none. */
    void revokeAllFor(String userId);
}

package com.skillmasterai.modules.account;

import java.util.Optional;

/**
 * Accounts and their passwords.
 *
 * <p>Everything here takes a phone number in and gives a {@link Account} — which carries no phone
 * number — back, so the credential's identifier does not travel any further than it has to.
 *
 * <p><strong>None of these opens a transaction.</strong> Registration has to write a namespace as
 * well, and §2.5 rule 2 puts the boundary for anything spanning modules in the use-case layer. Each
 * method is written to be called inside one and would be wrong to call outside one.
 */
public interface AccountRegistrar {

    /**
     * Creates an account and its password credential.
     *
     * <p>Does <strong>not</strong> create the personal namespace: that is M4's table and M4's rule
     * (§3.2), so the use case asks for it in the same transaction rather than this method reaching
     * across. The two must both exist or neither — an account whose namespace is missing has no
     * address to publish to, and the read path treats that as a database state the application
     * never writes.
     *
     * @throws AccountRequestException when the username is malformed or already taken, or the phone
     *         already has an account
     */
    Account register(String handle, String phone, String rawPassword);

    /**
     * Checks a password against the account that owns this phone number.
     *
     * @return empty for every way this can fail — no such phone, wrong password, suspended account.
     *         One answer for all three, because three answers are an account-existence oracle; see
     *         {@link com.skillmasterai.common.ErrorCode#INVALID_CREDENTIALS}.
     */
    Optional<Account> authenticate(String phone, String rawPassword);

    /**
     * Replaces the password of the account that owns this phone number.
     *
     * @return the account whose password changed — the caller needs its id to end its sessions —
     *         or empty when no account owns this number
     */
    Optional<Account> resetPassword(String phone, String rawPassword);
}

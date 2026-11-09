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
     * Whether an account already goes by this handle.
     *
     * <p>A read on a port named for writes — a wart, and the smaller one. It belongs here rather than
     * on {@link AccountDirectory} because that one is M4's window onto {@code app_user} and is kept
     * as narrow as M4's own need for it.
     *
     * <p>What it answers is a courtesy, never a guard: {@code UNIQUE(handle)} is what decides, and two
     * callers can pass this check in the same instant. It exists so a form can say a name is gone
     * before anybody spends a text message finding out.
     */
    boolean handleTaken(String handle);

    /**
     * Checks a password against the account that owns this phone number.
     *
     * @return empty for every way this can fail — no such phone, wrong password, suspended account.
     *         One answer for all three, because three answers are an account-existence oracle; see
     *         {@link com.skillmasterai.common.ErrorCode#INVALID_CREDENTIALS}. The one exception is a
     *         <em>deleted</em> account whose password is right: that is returned marked, because the
     *         caller already knows the password and the client can say something useful (v56). A
     *         wrong password on a deleted account is still empty.
     */
    Optional<SignIn> authenticate(String phone, String rawPassword);

    /**
     * Whether this password is the one this account signs in with.
     *
     * <p>For the delete flow (v56), which names the account by session and has no phone number to
     * look it up by. Unlike {@link #authenticate}, an absent password hash is a plain {@code false}
     * rather than a timing-equalised empty: the caller is already authenticated as this account, so
     * there is no existence to hide.
     */
    boolean passwordMatches(String userId, String rawPassword);

    /**
     * What a sign-in attempt found.
     *
     * @param account the account, whenever the password was right — including a deleted one, which
     *                the caller must not sign in
     * @param deleted whether this account was deleted; the password still had to be right
     */
    record SignIn(Account account, boolean deleted) {
    }

    /**
     * Replaces the password of the account that owns this phone number.
     *
     * @return the account whose password changed — the caller needs its id to end its sessions —
     *         or empty when no account owns this number
     */
    Optional<Account> resetPassword(String phone, String rawPassword);
}

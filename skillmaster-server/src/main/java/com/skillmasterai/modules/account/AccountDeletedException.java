package com.skillmasterai.modules.account;

/**
 * The password was right and the account is deleted.
 *
 * <p><strong>Distinct from {@link com.skillmasterai.common.ErrorCode#INVALID_CREDENTIALS}, and the
 * distinction is deliberate</strong> (v56, ADR 0175): this answer is only reachable by a caller who
 * already produced the correct password, so it is not an account-existence oracle. A wrong password
 * on a deleted account is still an ordinary 401 {@code invalid_credentials}.
 */
public final class AccountDeletedException extends RuntimeException {

    public AccountDeletedException() {
        super("the account was deleted");
    }
}

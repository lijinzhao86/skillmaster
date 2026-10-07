package com.skillmasterai.modules.account;

/**
 * Turns a password into something safe to store, and checks one against it.
 *
 * <p>A seam rather than a direct call to a framework class for the same reason
 * {@link com.skillmasterai.modules.auth.TokenValidator} is one: the algorithm is a decision that
 * should be able to change without the login path changing with it.
 */
public interface PasswordHasher {

    /** @return a self-contained hash — it carries its own salt and parameters */
    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);

    /**
     * Spends the same work as {@link #matches} without being able to succeed.
     *
     * <p>Called on the branch where there is no stored hash to compare against. Without it, a
     * login attempt against a phone nobody registered returns measurably faster than one against a
     * real account, and that difference is an account-existence oracle no amount of identical
     * response bodies hides.
     */
    void spendComparison(String rawPassword);
}

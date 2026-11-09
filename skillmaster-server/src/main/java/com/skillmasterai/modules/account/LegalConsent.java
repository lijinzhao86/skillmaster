package com.skillmasterai.modules.account;

/**
 * Records which versions of the legal texts an account accepted, and when (v56).
 *
 * <p><strong>The versions come from the server, never from the client.</strong> A request only
 * carries "I agreed"; the two version strings are whatever this deployment is currently serving, so
 * a client cannot claim to have accepted a version that does not exist — or, worse, a future one.
 */
public interface LegalConsent {

    /**
     * Writes the consent row for a freshly registered account.
     *
     * <p>Called in the same transaction as registration: an account that exists without a record of
     * what its owner agreed to is a registration this product must not be able to produce.
     */
    void record(String userId, String termsVersion, String privacyVersion, String at);
}

package com.skillmasterai.api;

import com.skillmasterai.modules.account.Account;

/**
 * What the browser plane says about the account it is acting as.
 *
 * <p>Three fields, and the third is not redundant: {@code namespace} is what the client needs to
 * build an address ({@code /api/v1/skills/<namespace>/…}), and today it happens to equal the
 * username because §3.2 makes the personal namespace's slug the handle. Sending both means the
 * client never has to know that rule, so the day a user can own more than one namespace — which
 * §3.2 already allows — the rule can change without a client change.
 *
 * <p>No phone number and no token. Neither is needed to answer "who am I", and both are values
 * worth not putting in a response body out of habit.
 */
record WebAccount(String userId, String username, String namespace) {

    static WebAccount of(Account account) {
        return new WebAccount(account.userId(), account.handle(), account.handle());
    }
}

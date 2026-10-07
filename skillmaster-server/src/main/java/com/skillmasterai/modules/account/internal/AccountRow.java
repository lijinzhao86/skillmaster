package com.skillmasterai.modules.account.internal;

/**
 * One {@code app_user} row, as M1's own code passes it around.
 *
 * <p>Package-private, and deliberately not the module's public {@link com.skillmasterai.modules.account.Account}:
 * this one carries {@code status}, which is a fact about whether an account may log in and is
 * therefore M1's business alone. Reflecting it outward would invite a caller to make a decision on
 * it, and the only decision the design wants made from it — refuse the login — is already made
 * before anything leaves this package.
 *
 * <p>Carries no phone number in any form. The lookup is by hash; the value never needs to be here.
 */
record AccountRow(String id, String handle, String status) {
}

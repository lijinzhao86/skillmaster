package com.skillmasterai.modules.account.internal;

/**
 * One {@code phone_verification} row, as far as checking a code needs to know.
 *
 * <p>Package-private because it is a shape for moving columns between two methods of one module,
 * not a thing M1 offers anyone.
 *
 * <p>{@code attempts} is not here, although the column is. It is claimed in a single conditional
 * {@code UPDATE} rather than read and then written, so the number never has to leave the database to
 * be acted on — see {@link PhoneVerificationRepository#claimAttempt}.
 */
record VerificationRow(String id, String codeHash, String expiresAt) {
}

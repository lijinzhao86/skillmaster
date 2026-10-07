package com.skillmasterai.modules.account.internal;

/**
 * One {@code captcha} row, as far as checking an answer needs to know.
 *
 * <p>Package-private because it is a shape for moving columns between two methods of one module, and
 * nothing outside M1 has any business holding a stored answer — not even the hash of one.
 *
 * <p>{@code attempts} is not here, although the column is. It is claimed in a single conditional
 * {@code UPDATE} rather than read and then written, so the number never has to leave the database to
 * be acted on — see {@link CaptchaRepository#claimAttempt}.
 *
 * @param id         the handle the client sent back
 * @param answerHash sha256 of the upper-cased answer, so the check is case-insensitive the way
 *                   somebody reading an image expects
 * @param expiresAt  RFC3339 UTC
 */
record CaptchaRow(String id, String answerHash, String expiresAt) {
}

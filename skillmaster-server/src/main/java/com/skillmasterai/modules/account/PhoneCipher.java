package com.skillmasterai.modules.account;

/**
 * The two forms M1 keeps a phone number in — neither of which is the number itself.
 *
 * <p>Why two: the app has to <em>find</em> an account by phone, which needs a deterministic value
 * to look up, and it will eventually have to <em>show</em> an owner their own number, which needs
 * the number back. A hash gives the first and not the second; a ciphertext gives the second and
 * cannot be indexed. Storing both is what the two columns are for, and it follows the reasoning
 * ADR 0007 already applied to tokens: a database leak should not be a leak of the credentials in it.
 */
public interface PhoneCipher {

    /**
     * The blind index: keyed, deterministic, and one-way.
     *
     * <p>Deterministic is the requirement, not a shortcoming — it is the value login looks up by,
     * and UNIQUE on the column is what makes one phone one account. Keyed rather than a bare digest
     * so that the value cannot be recovered by hashing a list of plausible numbers, which is a small
     * enough space to enumerate.
     */
    String hash(String phone);

    /** The ciphertext, for showing a number back to its owner and for nothing else. */
    String encrypt(String phone);

    /**
     * Recovers the number from {@link #encrypt}.
     *
     * <p>No production path calls this yet — the round has no screen that displays a phone number.
     * It exists because the column exists: storing a ciphertext whose format nothing ever reads is
     * storing a format nobody has checked, and the first caller would be the one to find out.
     */
    String decrypt(String encrypted);
}

package com.skillmasterai.modules.account;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt, through the framework's own encoder.
 *
 * <p>Not written here on purpose. Getting a password hash right means getting salt generation,
 * the cost parameter, and constant-time comparison right, and a library that already does all three
 * is the whole reason not to.
 *
 * <p><strong>BCrypt ignores everything past the 72nd byte of the password.</strong> That is a
 * property of the algorithm, not of this class, and unhandled it means two different long passwords
 * authenticate the same account. {@code PasswordPolicy} caps the length so the two can never differ,
 * and that cap is the whole of the defence.
 *
 * <p>It is worth being exact about where the cap is enforced, because it is not here. {@link
 * #hash} throws on anything longer — the framework's encoder refuses, which is what makes a caller
 * that hands it 73 bytes fail rather than absorb the mistake. {@link #matches} does not: it goes
 * through the comparison path, which truncates silently. So a password registered at exactly the
 * 72-byte ceiling will also match with anything appended to it. Nothing comes of that — the first
 * 72 bytes still have to be known — but the ceiling is a rule about the *registration* path, and
 * this class is not what holds it.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    /**
     * A hash of something nobody will ever type, compared against on the branch where there is no
     * account. Built once, because building it per attempt would cost what the attempt costs.
     */
    private final String hashOfNothing;

    /** @param strength the BCrypt cost. Configuration, because tests cannot afford the real one. */
    public BCryptPasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
        this.hashOfNothing = encoder.encode("no account has this password");
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }

    @Override
    public void spendComparison(String rawPassword) {
        encoder.matches(rawPassword, hashOfNothing);
    }
}

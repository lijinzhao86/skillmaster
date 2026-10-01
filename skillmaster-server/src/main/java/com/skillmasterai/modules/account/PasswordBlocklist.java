package com.skillmasterai.modules.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The passwords everybody picks, so that they can be refused.
 *
 * <p>This is what stands in place of character-class requirements (ADR 0016). NIST SP 800-63B asks
 * verifiers to check a memorized secret against a blocklist, and that is the rule with evidence
 * behind it: composition rules push people towards {@code Password1!} — which is in this list —
 * while refusing the long passphrase that is actually hard to crack.
 *
 * <p><strong>The data is vendored, not written here.</strong> Shipping a hand-typed list of "common
 * passwords" would be shipping an opinion with a number's authority. The file is taken verbatim from
 * SecLists ({@code Passwords/Common-Credentials/10k-most-common.txt}, MIT, © 2018 Daniel Miessler)
 * and is byte-identical to it:
 *
 * <ul>
 *   <li>source: {@code https://github.com/danielmiessler/SecLists} — raw file at
 *       {@code .../master/Passwords/Common-Credentials/10k-most-common.txt}</li>
 *   <li>sha256: {@code 68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce}</li>
 *   <li>retrieved: 2026-10-02; 10001 entries, none repeated, all ASCII</li>
 * </ul>
 *
 * <p><strong>What it does not cover.</strong> This list is Western-centric: it holds the passwords
 * that appear in leaked English-language corpora. Chinese-specific weak choices — a name in pinyin,
 * a birthday, {@code woaini1314} — are only partly in it, and no public list with a citable
 * provenance covers them well. The rules for "the password is your phone number or your handle with
 * something appended" ({@link PasswordPolicy}) are where that half is caught instead, and they are
 * the half a phone-number login makes most likely. Of the 10001 entries, **2087 are long enough to
 * ever be submitted** — the rest are for passwords below the eight-byte floor, which are already
 * refused for their length, so they are dead weight rather than a hole.
 */
public final class PasswordBlocklist {

    private final Set<String> entries;

    /** @param entries already lower-cased, one entry per password */
    PasswordBlocklist(Set<String> entries) {
        this.entries = Set.copyOf(entries);
    }

    /**
     * Reads the list from the classpath.
     *
     * <p>Both failures here are deliberately loud. A blocklist that quietly loaded nothing would be
     * a defence that looks present in every code review and is absent in every deployment, and the
     * only way anybody would find out is by guessing {@code password} successfully.
     *
     * @param minimumEntries what "plausibly the real list" means, so that a truncated or
     *                       half-written file stops the application instead of weakening it
     */
    public static PasswordBlocklist fromClasspath(String resource, int minimumEntries) {
        try (InputStream in = PasswordBlocklist.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("the password blocklist is not on the classpath: "
                        + resource + ". A blocklist that is silently missing is a defence that is "
                        + "silently absent.");
            }
            PasswordBlocklist blocklist = parse(in);
            if (blocklist.size() < minimumEntries) {
                throw new IllegalStateException("the password blocklist at " + resource + " holds "
                        + blocklist.size() + " entries, below the " + minimumEntries + " expected. "
                        + "Refusing to start with a blocklist that is probably not the one intended.");
            }
            return blocklist;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the password blocklist at " + resource, e);
        }
    }

    /** One entry per line, blanks ignored, compared in lower case. */
    static PasswordBlocklist parse(InputStream in) {
        Set<String> entries = new HashSet<>();
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                // strip() rather than trim(): a file that arrives with CRLF line endings would
                // otherwise load every entry with a trailing carriage return, and match nothing.
                String entry = line.strip();
                if (!entry.isEmpty()) {
                    entries.add(entry.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the password blocklist", e);
        }
        return new PasswordBlocklist(entries);
    }

    /** @param password as given, in any case: what is refused is the password, not its spelling */
    public boolean contains(String password) {
        return entries.contains(password.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return entries.size();
    }
}

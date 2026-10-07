package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The vendored list, and the ways loading it can fail without anybody noticing.
 *
 * <p>Every failure here has the same shape: a blocklist that is present in code review and absent in
 * the running application. Nothing refuses anything, no request errors, and the first evidence is
 * somebody else's account.
 */
class PasswordBlocklistTest {

    private static final String RESOURCE = "account/password-blocklist.txt";

    @Test
    void loadsTheRealList() {
        PasswordBlocklist blocklist = PasswordBlocklist.fromClasspath(RESOURCE, 1000);

        assertThat(blocklist.size()).isGreaterThan(9000);
        assertThat(blocklist.contains("password")).isTrue();
        assertThat(blocklist.contains("12345678")).isTrue();
    }

    @Test
    void comparesInOneCase() {
        // The upstream list carries both spellings, but a rule that depended on that would refuse
        // `password` and accept `PASSWORD`, which is not a difference an attacker has to care about.
        PasswordBlocklist blocklist = PasswordBlocklist.fromClasspath(RESOURCE, 1000);

        assertThat(blocklist.contains("PASSWORD")).isTrue();
        assertThat(blocklist.contains("PassWord")).isTrue();
    }

    @Test
    void isByteForByteTheFileTheClassNoteNames() throws Exception {
        byte[] bytes = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE).readAllBytes();

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .as("the vendored list changed, or it is no longer the revision PasswordBlocklist "
                        + "documents — update both together, so that what is loaded stays checkable")
                .isEqualTo("68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce");
    }

    @Test
    void parsesWhateverTheLineEndingsAndPaddingAre() {
        // A file that arrived with CRLF line endings would load every entry with a trailing carriage
        // return and match nothing — the same silent absence as a missing file, from a change that
        // looks like nothing.
        PasswordBlocklist blocklist = PasswordBlocklist.parse(new ByteArrayInputStream(
                "password\r\n\r\n  Qwerty123  \n12345678\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(blocklist.size()).isEqualTo(3);
        assertThat(blocklist.contains("password")).isTrue();
        assertThat(blocklist.contains("qwerty123")).isTrue();
    }

    @Test
    void refusesToStartOnSomethingThatIsNotTheList() {
        assertThatThrownBy(() -> PasswordBlocklist.fromClasspath("account/no-such-file.txt", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath");
        // And a file that is there but is not the list: truncated by a bad merge, or the wrong file
        // under the right name.
        assertThatThrownBy(() -> PasswordBlocklist.fromClasspath(RESOURCE, 100_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("below");
    }

    @Test
    void anEntryTooShortToSubmitIsRefusedForItsLengthRatherThanForBeingCommon() {
        // The two rules overlap on the shorter half of the list, and the order decides the answer:
        // "this password is too common" is a puzzling thing to tell somebody whose password is also
        // too short to have been accepted at all.
        PasswordBlocklist blocklist = PasswordBlocklist.fromClasspath(RESOURCE, 1000);
        assertThat(blocklist.contains("qwerty")).as("a real entry in the vendored list").isTrue();

        assertThat(PasswordPolicy.problemWith("qwerty", "demo-user", "13800138000", blocklist))
                .contains("too_short");
    }
}

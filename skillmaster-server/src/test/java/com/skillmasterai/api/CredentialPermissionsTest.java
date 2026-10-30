package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.skill.GrantRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The credential's half of {@code permissions} (v23).
 *
 * <p>What is pinned is the property the header promises: nothing that needs a write scope is listed
 * for a read-only key, and reads are never taken away — a key that could not read would not have
 * reached the response at all.
 */
class CredentialPermissionsTest {

    private static final List<String> ADMIN =
            List.of("read", "list", "write", "discard", "share");

    @Test
    void aWriteKeySeesEverythingItsRungAllows() {
        assertThat(CredentialPermissions.of(ADMIN, true)).isEqualTo(ADMIN);
    }

    @Test
    void aReadOnlyKeySeesOnlyTheReads() {
        // The default scope of a new key is `skills:read` (ADR 0038), so this is the common case,
        // not an edge one: without the intersection the header would promise four refusals.
        assertThat(CredentialPermissions.of(ADMIN, false)).containsExactly("read", "list");
    }

    @Test
    void aReadOnlyKeyLosesTheWriteCapabilities() {
        // v34 (ADR 0119): the same intersection on the capability list — commands that change
        // something are what the missing scope would refuse.
        assertThat(CredentialPermissions.ofCapabilities(
                GrantRole.capabilitiesOf(GrantRole.ADMIN), false))
                .extracting(GrantRole.Permission::capability)
                .containsExactly("read files", "list files");
    }

    @Test
    void aWriteKeyKeepsEveryCapability() {
        assertThat(CredentialPermissions.ofCapabilities(
                GrantRole.capabilitiesOf(GrantRole.ADMIN), true))
                .isEqualTo(GrantRole.capabilitiesOf(GrantRole.ADMIN));
    }

    @Test
    void theFilterNeverInventsAWord() {
        assertThat(CredentialPermissions.of(List.of("read"), false)).containsExactly("read");
        assertThat(CredentialPermissions.of(List.of(), false)).isEmpty();
    }
}

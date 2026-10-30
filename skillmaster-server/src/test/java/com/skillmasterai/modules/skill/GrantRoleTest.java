package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rung → permissions mapping, which is what {@code skill invoke} prints as its header (v23).
 *
 * <p>The assertions are deliberately about the <em>shape</em> rather than a table duplicated here:
 * {@link GrantRole} is the one place the ladder is written down, and a second copy of the answers in
 * a test would be a second place to update. What is pinned is that every rung answers, that nothing
 * outside the four action words ever appears, and that the ladder is cumulative.
 */
class GrantRoleTest {

    private static final List<String> KNOWN = List.of("read", "list", "write", "discard", "share");

    @Test
    void everyRungHasAPermissionsList() {
        for (String rung : GrantRole.all()) {
            assertThat(GrantRole.permissionsOf(rung))
                    .as("permissions for %s", rung)
                    .isNotEmpty()
                    .allSatisfy(permission -> assertThat(KNOWN).contains(permission));
        }
    }

    @Test
    void noRungMeansNoPermissions() {
        assertThat(GrantRole.permissionsOf(null)).isEmpty();
    }

    @Test
    void permissionsAreCumulativeAlongTheLadder() {
        List<String> previous = List.of();
        for (String rung : GrantRole.all()) {
            List<String> current = GrantRole.permissionsOf(rung);
            assertThat(current)
                    .as("%s includes everything below it", rung)
                    .containsAll(previous);
            previous = current;
        }
        // And the top of the ladder is where the sharing action appears; without this the test
        // would pass on a mapping that never added anything past `read`.
        assertThat(GrantRole.permissionsOf(GrantRole.ADMIN)).contains("share");
    }

    @Test
    void publishingAndVisibilityAreNotPermissions() {
        // Both are console actions with no CLI command; listing them would send a client looking for
        // something that does not exist (v23 PRD §不做 2).
        assertThat(GrantRole.permissionsOf(GrantRole.ADMIN))
                .doesNotContain("publish", "rollback", "visibility", "delete");
    }

    @Test
    void everyRungHasCapabilitiesThatNameTheirCommands() {
        // v34 (ADR 0119): the header's capabilities are what the agent acts on, so a capability
        // without a command, or a command that is not the shape of one, is a header it cannot use.
        for (String rung : GrantRole.all()) {
            assertThat(GrantRole.capabilitiesOf(rung))
                    .as("capabilities for %s", rung)
                    .isNotEmpty()
                    .allSatisfy(capability -> {
                        assertThat(capability.capability()).isNotBlank();
                        assertThat(capability.commands()).isNotEmpty().allSatisfy(command ->
                                assertThat(command).matches("(skill|version) [a-z-]+"));
                    });
        }
        assertThat(GrantRole.capabilitiesOf(null)).isEmpty();
    }

    @Test
    void capabilitiesAreCumulativeAlongTheLadder() {
        List<String> previous = List.of();
        for (String rung : GrantRole.all()) {
            List<String> current = GrantRole.capabilitiesOf(rung).stream()
                    .map(GrantRole.Permission::capability).toList();
            assertThat(current).as("%s includes everything below it", rung).containsAll(previous);
            previous = current;
        }
    }

    @Test
    void eachRungCarriesExactlyItsCapabilities() {
        // Cumulative assertions cannot catch over-inclusion: a rung that wrongly gained a higher
        // capability would still "contain everything below it". The exact sets are the mapping.
        assertThat(capabilityNames(GrantRole.INVOKE)).containsExactly("read files");
        assertThat(capabilityNames(GrantRole.BROWSE)).containsExactly("read files", "list files");
        assertThat(capabilityNames(GrantRole.WRITE))
                .containsExactly("read files", "list files", "write files");
        assertThat(capabilityNames(GrantRole.MAINTAIN))
                .containsExactly("read files", "list files", "write files", "discard drafts");
        assertThat(capabilityNames(GrantRole.ADMIN))
                .containsExactly("read files", "list files", "write files", "discard drafts",
                        "share this skill");
    }

    private static List<String> capabilityNames(String rung) {
        return GrantRole.capabilitiesOf(rung).stream()
                .map(GrantRole.Permission::capability).toList();
    }

    @Test
    void theInvokeRungCanReadButNotList() {
        // The same boundary `permissionsOf` draws: may read a file it can name, may not enumerate.
        assertThat(GrantRole.capabilitiesOf(GrantRole.INVOKE))
                .extracting(GrantRole.Permission::capability)
                .containsExactly("read files");
    }

    @Test
    void invokeIsTheReadOnlyRung() {
        // `GrantRole.INVOKE`'s own comment: may read a file it can name, may not list the directory.
        assertThat(GrantRole.permissionsOf(GrantRole.INVOKE)).containsExactly("read");
    }
}

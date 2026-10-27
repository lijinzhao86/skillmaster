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
    void invokeIsTheReadOnlyRung() {
        // `GrantRole.INVOKE`'s own comment: may read a file it can name, may not list the directory.
        assertThat(GrantRole.permissionsOf(GrantRole.INVOKE)).containsExactly("read");
    }
}

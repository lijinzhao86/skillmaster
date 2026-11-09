package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.notification.Notification;
import com.skillmasterai.modules.notification.NotificationKinds;
import org.junit.jupiter.api.Test;

/**
 * The displayed state of the announcement-shaped kinds (v56).
 *
 * <p>Without the {@code skill_unavailable} branch it fell to {@code pending}: the SQL that builds
 * the pending tab filters by kind, so the tab was right, but any count or client branch on
 * {@code state === 'pending'} treated a notice with nothing to answer as work.
 */
class NotificationViewAssemblerTest {

    private static Notification of(String kind) {
        return new Notification("n-1", kind, null, "u-1", "someone", "s-1", null, null, null,
                "title", "body", "2026-11-10T00:00:00Z", null, false);
    }

    @Test
    void aDeletedAuthorsSkillIsInformationNotWork() {
        assertThat(NotificationViewAssembler.stateOf(of(NotificationKinds.SKILL_UNAVAILABLE),
                "2026-11-10T00:00:00Z")).isEqualTo("info");
    }

    @Test
    void anAnnouncementIsStillInformation() {
        assertThat(NotificationViewAssembler.stateOf(of(NotificationKinds.ANNOUNCEMENT),
                "2026-11-10T00:00:00Z")).isEqualTo("info");
    }
}

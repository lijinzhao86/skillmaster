package com.skillmasterai.modules.notification;

/** The kinds this version emits. The column is open; these names are the client's vocabulary. */
public final class NotificationKinds {

    public static final String SHARE_INVITATION = "share_invitation";
    public static final String ACCESS_REQUEST = "access_request";
    public static final String ACCESS_REQUEST_RESULT = "access_request_result";
    public static final String ANNOUNCEMENT = "announcement";
    /**
     * A skill somebody could use has stopped working because its author deleted their account
     * (v56, ADR 0175). Announcement-shaped: no expiry, no accept/decline, and the subject is the
     * skill rather than a request.
     */
    public static final String SKILL_UNAVAILABLE = "skill_unavailable";
    /**
     * A public skill entered its 下线中 ramp (v61, ADR 0184). Announcement-shaped, with the
     * deadline in {@code expires_at}; the client renders it as a clock row and offers
     * 「复制到我的库」.
     */
    public static final String SKILL_OFFLINE_SCHEDULED = "skill_offline_scheduled";

    private NotificationKinds() {
    }
}

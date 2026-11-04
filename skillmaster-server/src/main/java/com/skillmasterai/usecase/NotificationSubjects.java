package com.skillmasterai.usecase;

/**
 * The subject keys M14 stores, in one place.
 *
 * <p>The convention lives in the use-case layer because it is a business fact: M14 treats
 * {@code subject_key} as an opaque string, and every emitter has to agree with every resolver or a
 * notification would never close. Writing it once is what keeps those two sides from drifting.
 */
final class NotificationSubjects {

    private NotificationSubjects() {
    }

    static String invitation(String skillId, String inviteeId) {
        return "invitation:" + skillId + ":" + inviteeId;
    }

    static String accessRequest(String skillId, String requesterId) {
        return "access_request:" + skillId + ":" + requesterId;
    }
}

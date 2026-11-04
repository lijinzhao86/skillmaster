package com.skillmasterai.usecase;

/**
 * One notification as a client reads it: the event, its state, and whether its skill can be opened
 * right now.
 *
 * @param state    {@code pending}, {@code info}, or one of {@code NotificationOutcome}'s wire names
 * @param actor    the actor's handle at write time; null on announcements
 * @param skill    the address; null on announcements and when the skill row is gone
 * @param resolvedAt when the business object was settled; null while it still waits
 * @param viewable whether this caller may read the skill now — the console decides which states
 *                 show 「查看」; this field says whether the link would work
 */
public record NotificationView(
        String id,
        String kind,
        String state,
        String actor,
        SkillView skill,
        String role,
        String title,
        String body,
        String createdAt,
        String resolvedAt,
        String expiresAt,
        boolean read,
        boolean viewable) {

    public record SkillView(String namespace, String name) {
    }
}

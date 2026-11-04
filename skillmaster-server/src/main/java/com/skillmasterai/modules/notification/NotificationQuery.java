package com.skillmasterai.modules.notification;

import java.util.Set;

/**
 * A recipient's feed query.
 *
 * <p>{@code administeredSkillIds} is supplied by the use case because M14 may not read M12's
 * tables. It filters the management-shaped notifications: an {@code access_request} row disappears
 * from a recipient's feed once they lose the rung that made them a recipient of it. The filter is
 * in the query rather than applied afterwards so the unread count and the total agree with the
 * page.
 *
 * @param status one of {@code attention} (pending or unread), {@code pending}, or {@code all}
 * @param page   1-based
 */
public record NotificationQuery(
        String userId,
        String status,
        boolean unreadOnly,
        int page,
        int per,
        Set<String> administeredSkillIds,
        String now) {

    public enum Status {
        ATTENTION("attention"),
        PENDING("pending"),
        ALL("all");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Status fromWire(String value) {
            if (value == null || value.isBlank()) {
                return ATTENTION;
            }
            return switch (value) {
                case "attention" -> ATTENTION;
                case "pending" -> PENDING;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException(
                        "status must be attention, pending or all, was '" + value + "'");
            };
        }
    }
}

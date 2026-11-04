package com.skillmasterai.modules.notification;

import java.util.List;

/**
 * One page of a recipient's feed, plus the two counts the console needs.
 *
 * @param total  how many rows match the current filter, not the page size
 * @param unread how many of this recipient's visible notifications are unread, across all filters
 */
public record NotificationPage(List<Notification> items, long total, long unread) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}

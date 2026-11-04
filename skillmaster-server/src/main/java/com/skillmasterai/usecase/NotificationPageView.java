package com.skillmasterai.usecase;

import java.util.List;

/** A page of the feed with the two counts the console and CLI show. */
public record NotificationPageView(List<NotificationView> items, long total, long unread) {

    public NotificationPageView {
        items = List.copyOf(items);
    }
}

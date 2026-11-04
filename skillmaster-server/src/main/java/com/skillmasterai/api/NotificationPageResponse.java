package com.skillmasterai.api;

import com.skillmasterai.usecase.NotificationPageView;
import java.util.List;

/** One page of the feed: the rows, how many match, and how many are unread in total. */
record NotificationPageResponse(List<NotificationResponse> items, long total, long unread) {

    static NotificationPageResponse of(NotificationPageView page) {
        return new NotificationPageResponse(NotificationResponse.list(page.items()),
                page.total(), page.unread());
    }
}

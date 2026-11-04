package com.skillmasterai.api;

import com.skillmasterai.usecase.NotificationView;
import java.util.List;

/** The wire shape of one notification (v45 technical design §接口). */
record NotificationResponse(
        String id,
        String kind,
        String state,
        String actor,
        Skill skill,
        String role,
        String title,
        String body,
        String created_at,
        String resolved_at,
        String expires_at,
        boolean read,
        boolean viewable) {

    record Skill(String namespace, String name) {
    }

    static NotificationResponse of(NotificationView view) {
        return new NotificationResponse(
                view.id(),
                view.kind(),
                view.state(),
                view.actor(),
                view.skill() == null ? null
                        : new Skill(view.skill().namespace(), view.skill().name()),
                view.role(),
                view.title(),
                view.body(),
                view.createdAt(),
                view.resolvedAt(),
                view.expiresAt(),
                view.read(),
                view.viewable());
    }

    static List<NotificationResponse> list(List<NotificationView> views) {
        return views.stream().map(NotificationResponse::of).toList();
    }
}

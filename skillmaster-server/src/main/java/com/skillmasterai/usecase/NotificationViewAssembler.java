package com.skillmasterai.usecase;

import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.Notification;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAddress;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns stored notifications into what a client reads, in one batch.
 *
 * <p>Three facts are not on the notification row and are all asked once per page: the skill's
 * address (M12), the namespace's slug (M4), and whether the caller may read each skill now (M12).
 * Doing that per row would make a ten-row page thirty round trips; doing it in the API layer would
 * put the composition in the wrong place.
 */
@Component
class NotificationViewAssembler {

    private final NamespaceService namespaces;
    private final SkillMetadataService skills;

    NotificationViewAssembler(NamespaceService namespaces, SkillMetadataService skills) {
        this.namespaces = namespaces;
        this.skills = skills;
    }

    NotificationView assemble(Notification row, Caller caller, String now) {
        return assemble(List.of(row), caller, now).get(0);
    }

    List<NotificationView> assemble(List<Notification> rows, Caller caller, String now) {
        Set<String> skillIds = rows.stream()
                .map(Notification::skillId)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toUnmodifiableSet());

        Map<String, SkillAddress> addresses = skills.addressesOf(skillIds);
        Set<String> namespaceIds = addresses.values().stream()
                .map(SkillAddress::namespaceId)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, String> slugs = namespaces.slugsOf(namespaceIds);
        Set<String> readable = skills.readableAmong(caller, skillIds);

        return rows.stream().map(row -> {
            NotificationView.SkillView address = null;
            String skillId = row.skillId();
            if (skillId != null) {
                SkillAddress found = addresses.get(skillId);
                if (found != null) {
                    String slug = slugs.get(found.namespaceId());
                    if (slug != null) {
                        address = new NotificationView.SkillView(slug, found.name());
                    }
                }
            }
            return new NotificationView(
                    row.id(),
                    row.kind(),
                    stateOf(row, now),
                    row.actorHandle(),
                    address,
                    row.role(),
                    row.title(),
                    row.body(),
                    row.createdAt(),
                    row.resolvedAt(),
                    row.expiresAt(),
                    row.read(),
                    skillId != null && readable.contains(skillId));
        }).toList();
    }

    /**
     * The displayed state.
     *
     * <p>A resolved row shows its outcome; an announcement is information, never work; an
     * invitation past its {@code expires_at} reads as expired without any background task having
     * run. Everything else is still waiting.
     */
    static String stateOf(Notification row, String now) {
        if (row.outcome() != null) {
            return row.outcome();
        }
        if (NotificationKinds.ANNOUNCEMENT.equals(row.kind())) {
            return "info";
        }
        if (NotificationKinds.SHARE_INVITATION.equals(row.kind())
                && row.expiresAt() != null && row.expiresAt().compareTo(now) <= 0) {
            return "expired";
        }
        return "pending";
    }
}

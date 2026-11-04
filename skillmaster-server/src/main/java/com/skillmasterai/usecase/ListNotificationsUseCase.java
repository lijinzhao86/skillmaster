package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationPage;
import com.skillmasterai.modules.notification.NotificationQuery;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A recipient's feed — the console's notification page and the CLI's default list.
 *
 * <p>The page, its total and the unread count all come from one transaction and one visibility
 * set. That is what makes the bell's badge agree with {@code list --unread}: they are not two
 * queries that happen to be close in time.
 */
@Component
public class ListNotificationsUseCase {

    static final int DEFAULT_PER = 10;
    static final int MAX_PER = 50;

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final NotificationViewAssembler assembler;

    public ListNotificationsUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications, NotificationViewAssembler assembler) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public NotificationPageView list(String status, boolean unreadOnly, Integer pageNumber,
            Integer per, AuthenticatedSubject subject) {
        NotificationQuery.Status parsed = statusOf(status);
        int p = pageNumber == null ? 1 : pageNumber;
        if (p < 1) {
            throw new RequestException(ErrorCode.INVALID_REQUEST, "page must be at least 1");
        }
        int size = per == null ? DEFAULT_PER : per;
        if (size < 1 || size > MAX_PER) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "per must be between 1 and " + MAX_PER);
        }

        Caller caller = Callers.of(namespaces, subject);
        Set<String> administered = access.administeredSkillIds(caller);
        String now = Timestamps.now();
        NotificationPage found = notifications.page(new NotificationQuery(subject.userId(),
                parsed.wireName(), unreadOnly, p, size, administered, now));
        return new NotificationPageView(assembler.assemble(found.items(), caller, now),
                found.total(), found.unread());
    }

    private static NotificationQuery.Status statusOf(String status) {
        try {
            return NotificationQuery.Status.fromWire(status);
        } catch (IllegalArgumentException e) {
            throw new RequestException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}

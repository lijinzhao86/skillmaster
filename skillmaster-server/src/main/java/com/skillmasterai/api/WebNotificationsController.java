package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.AcceptSkillInvitationUseCase;
import com.skillmasterai.usecase.DeclineSkillInvitationUseCase;
import com.skillmasterai.usecase.ListNotificationsUseCase;
import com.skillmasterai.usecase.MarkAllNotificationsReadUseCase;
import com.skillmasterai.usecase.MarkNotificationReadUseCase;
import com.skillmasterai.usecase.NotificationView;
import com.skillmasterai.usecase.ReadNotificationUseCase;
import com.skillmasterai.usecase.UnreadNotificationCountUseCase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser plane's notification centre (v45).
 *
 * <p>It carries no approve / deny route: the console's decision screen is the collaborators pane,
 * which shows the requester's current rung and lets the approver change it before granting. The
 * notification row links there; the CLI is the plane that answers from the notification itself
 * (ADR 0140).
 */
@RestController
@RequestMapping(path = "/web/notifications")
class WebNotificationsController {

    private final ListNotificationsUseCase listNotifications;
    private final ReadNotificationUseCase readNotification;
    private final UnreadNotificationCountUseCase unreadCount;
    private final MarkNotificationReadUseCase markRead;
    private final MarkAllNotificationsReadUseCase markAllRead;
    private final AcceptSkillInvitationUseCase acceptInvitation;
    private final DeclineSkillInvitationUseCase declineInvitation;

    WebNotificationsController(ListNotificationsUseCase listNotifications,
            ReadNotificationUseCase readNotification,
            UnreadNotificationCountUseCase unreadCount,
            MarkNotificationReadUseCase markRead,
            MarkAllNotificationsReadUseCase markAllRead,
            AcceptSkillInvitationUseCase acceptInvitation,
            DeclineSkillInvitationUseCase declineInvitation) {
        this.listNotifications = listNotifications;
        this.readNotification = readNotification;
        this.unreadCount = unreadCount;
        this.markRead = markRead;
        this.markAllRead = markAllRead;
        this.acceptInvitation = acceptInvitation;
        this.declineInvitation = declineInvitation;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationPageResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(name = "unread", required = false, defaultValue = "false")
            boolean unread,
            @RequestParam(required = false) Integer p,
            @RequestParam(required = false) Integer per,
            Authentication caller) {
        return ResponseEntity.ok(NotificationPageResponse.of(listNotifications.list(status,
                unread, p, per, AuthenticatedSubject.ofSession(caller.getName()))));
    }

    @GetMapping(path = "/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Unread> unread(Authentication caller) {
        return ResponseEntity.ok(new Unread(unreadCount.unread(
                AuthenticatedSubject.ofSession(caller.getName()))));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationResponse> show(@PathVariable String id, Authentication caller) {
        return ResponseEntity.ok(NotificationResponse.of(readNotification.show(id,
                AuthenticatedSubject.ofSession(caller.getName()))));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable String id, Authentication caller) {
        markRead.markRead(id, AuthenticatedSubject.ofSession(caller.getName()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Marked> readAll(Authentication caller) {
        return ResponseEntity.ok(new Marked(markAllRead.markAll(
                AuthenticatedSubject.ofSession(caller.getName()))));
    }

    @PostMapping(path = "/{id}/accept", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> accept(@PathVariable String id, Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        AcceptSkillInvitationUseCase.Result result = acceptInvitation.accept(id, subject);
        if ("expired".equals(result.state())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(expired());
        }
        if ("revoked".equals(result.state())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(revoked());
        }
        NotificationView view = readNotification.show(id, subject);
        return ResponseEntity.ok(new Accepted(view.skill(), result.role()));
    }

    @PostMapping("/{id}/decline")
    ResponseEntity<Void> decline(@PathVariable String id, Authentication caller) {
        declineInvitation.decline(id, AuthenticatedSubject.ofSession(caller.getName()));
        return ResponseEntity.noContent().build();
    }

    private static ApiError expired() {
        return ApiError.of(ErrorCode.INVALID_NOTIFICATION_STATE,
                "this invitation has expired", "reason", "expired");
    }

    private static ApiError revoked() {
        return ApiError.of(ErrorCode.INVALID_NOTIFICATION_STATE,
                "the skill this invitation names was deleted", "reason", "revoked");
    }

    record Unread(long unread) {
    }

    record Marked(int marked) {
    }

    record Accepted(NotificationView.SkillView skill, String role) {
    }
}

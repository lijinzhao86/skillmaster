package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.AcceptSkillInvitationUseCase;
import com.skillmasterai.usecase.ApproveAccessRequestByNotificationUseCase;
import com.skillmasterai.usecase.DeclineSkillInvitationUseCase;
import com.skillmasterai.usecase.DecideSkillAccessRequestUseCase;
import com.skillmasterai.usecase.ListNotificationsUseCase;
import com.skillmasterai.usecase.MarkAllNotificationsReadUseCase;
import com.skillmasterai.usecase.MarkNotificationReadUseCase;
import com.skillmasterai.usecase.NotificationView;
import com.skillmasterai.usecase.ReadNotificationUseCase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API plane's notification feed and the actions that answer a notification (v45).
 *
 * <p>Thin, like the other controllers: the visibility of management-shaped notifications, the
 * expiry check and the business write all happen in the use cases. What is left here is parsing a
 * query string and choosing a status.
 */
@RestController
@RequestMapping(path = "/api/v1/notifications")
class NotificationsController {

    private final ListNotificationsUseCase listNotifications;
    private final ReadNotificationUseCase readNotification;
    private final MarkNotificationReadUseCase markRead;
    private final MarkAllNotificationsReadUseCase markAllRead;
    private final AcceptSkillInvitationUseCase acceptInvitation;
    private final DeclineSkillInvitationUseCase declineInvitation;
    private final ApproveAccessRequestByNotificationUseCase decideRequest;

    NotificationsController(ListNotificationsUseCase listNotifications,
            ReadNotificationUseCase readNotification,
            MarkNotificationReadUseCase markRead,
            MarkAllNotificationsReadUseCase markAllRead,
            AcceptSkillInvitationUseCase acceptInvitation,
            DeclineSkillInvitationUseCase declineInvitation,
            ApproveAccessRequestByNotificationUseCase decideRequest) {
        this.listNotifications = listNotifications;
        this.readNotification = readNotification;
        this.markRead = markRead;
        this.markAllRead = markAllRead;
        this.acceptInvitation = acceptInvitation;
        this.declineInvitation = declineInvitation;
        this.decideRequest = decideRequest;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationPageResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(name = "unread", required = false, defaultValue = "false")
            boolean unread,
            @RequestParam(required = false) Integer p,
            @RequestParam(required = false) Integer per,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(NotificationPageResponse.of(
                listNotifications.list(status, unread, p, per, subject)));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationResponse> show(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(NotificationResponse.of(readNotification.show(id, subject)));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        markRead.markRead(id, subject);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Marked> readAll(@AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(new Marked(markAllRead.markAll(subject)));
    }

    /**
     * Accepts an invitation. A 409 is an ordinary answer here, not an exception: the use case has
     * to commit the expired notification and the deleted invitation row before saying so, and a
     * thrown exception would roll both back.
     */
    @PostMapping(path = "/{id}/accept", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> accept(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
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
    ResponseEntity<Void> decline(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        declineInvitation.decline(id, subject);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Accepted> approve(@PathVariable String id,
            @RequestBody(required = false) NotificationApprovalRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        DecideSkillAccessRequestUseCase.Decision decision = decideRequest.decide(id,
                body == null ? null : body.role(), true, subject);
        NotificationView view = readNotification.show(id, subject);
        return ResponseEntity.ok(new Accepted(view.skill(), decision.granted()));
    }

    @PostMapping("/{id}/deny")
    ResponseEntity<Void> deny(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        decideRequest.decide(id, null, false, subject);
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

    record Marked(int marked) {
    }

    /** The answer to accept and approve: where it points, and the rung that now holds. */
    record Accepted(NotificationView.SkillView skill, String role) {
    }
}

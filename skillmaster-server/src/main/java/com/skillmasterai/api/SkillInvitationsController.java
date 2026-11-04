package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ListSkillInvitationsUseCase;
import com.skillmasterai.usecase.RevokeSkillInvitationUseCase;
import com.skillmasterai.usecase.ShareSkillUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitations on the API plane: offer, list and withdraw (v45, ADR 0138).
 *
 * <p>Accepting and declining are deliberately <strong>not</strong> here. The invitee answers from
 * their notification, because that is where they were told; the inviter's view is this one, and it
 * can only look and withdraw.
 */
@RestController
@RequestMapping(path = SkillRoutes.BASE)
class SkillInvitationsController {

    private final ShareSkillUseCase shareSkill;
    private final ListSkillInvitationsUseCase listInvitations;
    private final RevokeSkillInvitationUseCase revokeInvitation;

    SkillInvitationsController(ShareSkillUseCase shareSkill,
            ListSkillInvitationsUseCase listInvitations,
            RevokeSkillInvitationUseCase revokeInvitation) {
        this.shareSkill = shareSkill;
        this.listInvitations = listInvitations;
        this.revokeInvitation = revokeInvitation;
    }

    /**
     * Offers a rung. 201 when an invitation was written; 200 when the invitee already holds it.
     *
     * <p>The second answer is not a failure: there is nothing to accept, and the caller asked for
     * a state that already holds.
     */
    @PostMapping(path = SkillRoutes.INVITATIONS,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<InvitationResponse> invite(@PathVariable String namespace,
            @PathVariable String name, @RequestBody SkillGrantRequest request,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        ShareSkillUseCase.Invited invited = shareSkill
                .share(namespace, name, request.handle(), request.role(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(invited.alreadyGranted() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(InvitationResponse.of(invited));
    }

    /** The invitations still waiting — the {@code admin} rung's view. */
    @GetMapping(path = SkillRoutes.INVITATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillInvitationsResponse> list(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(SkillInvitationsResponse.of(listInvitations
                .list(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new)));
    }

    /** Withdraws a waiting invitation. Idempotent, like withdrawing a grant. */
    @DeleteMapping(SkillRoutes.ONE_INVITATION)
    ResponseEntity<Void> revoke(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, @AuthenticationPrincipal AuthenticatedSubject subject) {
        revokeInvitation.revoke(namespace, name, handle, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }
}

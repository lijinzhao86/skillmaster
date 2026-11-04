package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ListSkillInvitationsUseCase;
import com.skillmasterai.usecase.RevokeSkillInvitationUseCase;
import com.skillmasterai.usecase.ShareSkillUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invitations on the browser plane — the same three actions as {@link SkillInvitationsController}. */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebSkillInvitationsController {

    private final ShareSkillUseCase shareSkill;
    private final ListSkillInvitationsUseCase listInvitations;
    private final RevokeSkillInvitationUseCase revokeInvitation;

    WebSkillInvitationsController(ShareSkillUseCase shareSkill,
            ListSkillInvitationsUseCase listInvitations,
            RevokeSkillInvitationUseCase revokeInvitation) {
        this.shareSkill = shareSkill;
        this.listInvitations = listInvitations;
        this.revokeInvitation = revokeInvitation;
    }

    @PostMapping(path = WebSkillRoutes.INVITATIONS,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<InvitationResponse> invite(@PathVariable String namespace,
            @PathVariable String name, @RequestBody SkillGrantRequest request,
            Authentication caller) {
        ShareSkillUseCase.Invited invited = shareSkill
                .share(namespace, name, request.handle(), request.role(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(invited.alreadyGranted() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(InvitationResponse.of(invited));
    }

    @GetMapping(path = WebSkillRoutes.INVITATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillInvitationsResponse> list(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return ResponseEntity.ok(SkillInvitationsResponse.of(listInvitations
                .list(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
    }

    @DeleteMapping(WebSkillRoutes.ONE_INVITATION)
    ResponseEntity<Void> revoke(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, Authentication caller) {
        revokeInvitation.revoke(namespace, name, handle,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }
}

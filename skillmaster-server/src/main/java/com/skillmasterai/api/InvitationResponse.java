package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skillmasterai.usecase.ShareSkillUseCase;

/**
 * The answer to a share: an invitation was written, or the invitee already held the rung.
 *
 * <p>The two answers have different fields on purpose. A new invitation carries when it expires;
 * an already-granted answer carries the rung they hold and no expiry, because nothing was written.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record InvitationResponse(String handle, String role, String expires_at, Boolean already_granted) {

    static InvitationResponse of(ShareSkillUseCase.Invited invited) {
        return invited.alreadyGranted()
                ? new InvitationResponse(invited.handle(), invited.role(), null, true)
                : new InvitationResponse(invited.handle(), invited.role(), invited.expiresAt(), null);
    }
}

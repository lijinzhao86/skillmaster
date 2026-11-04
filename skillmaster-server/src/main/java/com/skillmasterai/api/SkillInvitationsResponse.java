package com.skillmasterai.api;

import com.skillmasterai.usecase.ListSkillInvitationsUseCase;
import java.util.List;

/** Every invitation on one skill — the inviter's view (v45). */
record SkillInvitationsResponse(List<Invitation> items) {

    /** @param state {@code pending} or {@code expired}, computed from {@code expires_at} */
    record Invitation(String handle, String role, String created_at, String expires_at,
            String state) {
    }

    static SkillInvitationsResponse of(List<ListSkillInvitationsUseCase.View> rows) {
        return new SkillInvitationsResponse(rows.stream()
                .map(row -> new Invitation(row.handle(), row.role(), row.createdAt(),
                        row.expiresAt(), row.state()))
                .toList());
    }
}

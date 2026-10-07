package com.skillmasterai.api;

import com.skillmasterai.usecase.ListSkillGrantsUseCase.SharedWith;
import java.util.List;

/**
 * Who a skill is shared with — the owner's view of one skill (ADR 0034).
 *
 * <p>Handles, not user ids: the caller is a person looking at their own skill and deciding whether
 * to withdraw something, and a list of ULIDs would be a list they cannot act on.
 *
 * <p>A wrapper object rather than a bare array, so that a field can be added to this answer without
 * changing its shape — the same reason {@code next_cursor} lives beside {@code skills} rather than
 * the listing being an array.
 */
record SkillGrantsResponse(List<Grant> grants) {

    record Grant(String handle, String role, String created_at) {
    }

    static SkillGrantsResponse of(List<SharedWith> grants) {
        return new SkillGrantsResponse(grants.stream()
                .map(grant -> new Grant(grant.handle(), grant.role(), grant.createdAt()))
                .toList());
    }
}

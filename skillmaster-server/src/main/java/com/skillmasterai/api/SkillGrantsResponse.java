package com.skillmasterai.api;

import com.skillmasterai.usecase.ListSkillGrantsUseCase.GrantPage;
import java.util.List;

/**
 * Who a skill is shared with — the administrator's view of one skill (ADR 0034, ADR 0047).
 *
 * <p>Handles, not user ids: the caller is a person looking at their own skill and deciding whether
 * to withdraw something, and a list of ULIDs would be a list they cannot act on.
 *
 * <p>A wrapper object rather than a bare array, so that a field can be added to this answer without
 * changing its shape — the same reason {@code next_cursor} lives beside {@code skills} rather than
 * the listing being an array. The page is cut on the server because the design's address carries
 * {@code &p=} and {@code &q=}: a client that pulled everything to slice it would be inventing a
 * page the server never agreed to.
 */
record SkillGrantsResponse(List<Grant> grants, int total, int page, int pageSize) {

    record Grant(String handle, String role, String created_at) {
    }

    static SkillGrantsResponse of(GrantPage result) {
        return new SkillGrantsResponse(result.grants().stream()
                .map(grant -> new Grant(grant.handle(), grant.role(), grant.createdAt()))
                .toList(),
                result.total(), result.page(), result.pageSize());
    }
}

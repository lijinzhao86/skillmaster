package com.skillmasterai.api;

import com.skillmasterai.usecase.ListSkillAccessRequestsUseCase.WaitingWith;
import java.util.List;

/**
 * The pending requests on one skill, oldest first (ADR 0052).
 *
 * <p>A wrapper object with a total, for the reason the grant list is one: the design's secondary
 * menu carries a count, and a bare array would make adding that count a breaking change.
 */
record SkillAccessRequestsResponse(List<Request> requests, int total) {

    record Request(String handle, String wantedRole, String createdAt) {
    }

    static SkillAccessRequestsResponse of(List<WaitingWith> requests) {
        return new SkillAccessRequestsResponse(requests.stream()
                .map(request -> new Request(request.handle(), request.wantedRole(),
                        request.createdAt()))
                .toList(), requests.size());
    }
}

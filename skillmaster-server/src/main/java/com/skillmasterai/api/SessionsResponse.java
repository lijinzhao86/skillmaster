package com.skillmasterai.api;

import java.util.List;

/**
 * The caller's logins, and nothing else.
 *
 * <p>Wrapped in an object rather than sent as a bare array, for the reason the error envelope and
 * {@link ApiKeysResponse} are: a top-level array cannot grow a sibling, and this screen has already
 * grown one thing the design wanted counted rather than derived.
 *
 * <p><strong>There is deliberately no count here.</strong> The console's 「3 台设备」 is the length of
 * this list, and a server-side total would be a second answer to the same question — wrong the
 * moment the two are computed a request apart. The one number the server does send is the result of
 * a write ({@link SessionsRevoked}), because that one is about an action and not about a list.
 */
record SessionsResponse(List<SessionResponse> sessions) {

    SessionsResponse {
        sessions = List.copyOf(sessions);
    }
}

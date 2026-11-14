package com.skillmasterai.api;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.token.CliSignIn;
import java.util.List;

/**
 * What the approval page needs to draw the question, and nothing that could redeem it.
 *
 * <p><strong>No ticket, no secret, no authorization id.</strong> The body is rendered by a browser
 * that followed a link; the only thing it may be trusted with is what it has to show. The ticket is
 * already in the address and is not echoed back, and the other two are never in a page.
 *
 * <p>{@code ageMinutes} is how long ago the question was asked, as this server counted it — the page
 * would otherwise compute it from a clock it does not own.
 *
 * <p>{@code from} is where the request came from, as the server saw it, or null when the socket did
 * not expose one. It is the one fact on the page the CLI cannot make up, and the page omits the line
 * rather than guessing when it is absent.
 */
record CliDeviceSignInViewResponse(String status, String name, String client, List<String> scopes,
        String from, String createdAt, String expiresAt, long ageMinutes) {

    static CliDeviceSignInViewResponse of(CliSignIn.PublicView view) {
        return new CliDeviceSignInViewResponse(view.status(), view.name(), view.client(),
                List.copyOf(view.scopes()), view.from(), Timestamps.format(view.createdAt()),
                Timestamps.format(view.expiresAt()), view.ageMinutes());
    }
}

package com.skillmasterai.api;

import com.skillmasterai.modules.account.LoginDevice;
import java.time.Instant;

/**
 * One login, as the browser plane describes it — {@link LoginDevice} with one field changed.
 *
 * <p>Field names are the ones §接口 fixes ({@code device_id}, {@code logged_in_at}, …) and the
 * times are the stored spellings rendered as text, in the shape {@link ApiKeyResponse} already
 * sends them: RFC3339 UTC, which the console parses with {@code new Date(...)} and formats for the
 * reader's locale. Nothing here reformats a timestamp into a sentence — that is the screen's job,
 * and there is exactly one screen.
 *
 * <p><strong>{@code device} is the only field that is not a stored value.</strong> The session keeps
 * the User-Agent verbatim; this is where it becomes something readable
 * ({@link DeviceDescription}), because the layer that turns stored facts into what a screen shows is
 * this one. It is empty rather than absent when the client sent no User-Agent at all — a browser
 * that says nothing about itself is a fact, and the console draws no second line for it.
 *
 * @param deviceId   the handle the console revokes by. <strong>Null for a session created before
 *                   this version</strong>, which carried no stamp; such a row can be seen and
 *                   cannot be ended, and the console is what decides that
 * @param address    where the login came from, or null when nothing was readable
 * @param current    whether this is the session making the request — never its id, which does not
 *                   leave the server
 */
record SessionResponse(
        String deviceId,
        String device,
        String address,
        String loggedInAt,
        String lastSeenAt,
        boolean current) {

    static SessionResponse of(LoginDevice session) {
        return new SessionResponse(
                session.deviceId(),
                DeviceDescription.of(session.userAgent()),
                session.address(),
                text(session.loggedInAt()),
                text(session.lastSeenAt()),
                session.current());
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }
}

package com.skillmasterai.api;

import com.skillmasterai.modules.account.LoginDevice;
import com.skillmasterai.modules.token.CliSignIn;
import java.time.Instant;

/**
 * One login, as the browser plane describes it.
 *
 * <p>Field names are the ones §接口 fixes ({@code device_id}, {@code logged_in_at}, …) and the
 * times are the stored spellings rendered as text, in the shape {@link ApiKeyResponse} already
 * sends them: RFC3339 UTC, which the console parses with {@code new Date(...)} and formats for the
 * reader's locale. Nothing here reformats a timestamp into a sentence — that is the screen's job,
 * and there is exactly one screen.
 *
 * <p><strong>Two kinds of login, one row.</strong> A browser session and a CLI login appear on the
 * same screen, in the same list, counted by the same number (v4 Q6): they are one kind of thing — a
 * machine that may act as this person, with the same two rulers and the same action to end it. The
 * two factories below are the two things that produce one, and {@link #kind} is the only thing the
 * screen branches on, because it decides which address the 退出 button posts to. It is not a mark:
 * the design's rule for that screen is that an active row says nothing about itself, and the row
 * that is a CLI login is already told apart by its own second segment.
 *
 * <p><strong>{@code device} is the only field that is not a stored value.</strong> A session keeps
 * the User-Agent verbatim and this is where it becomes something readable
 * ({@link DeviceDescription}); a CLI login carries the name the person typed, and this is where the
 * screen's own spelling of it is put on the front. It is empty rather than absent when the client
 * sent no User-Agent at all — a browser that says nothing about itself is a fact, and the console
 * draws no second line for it.
 *
 * @param kind       which plane this login's way out lives on, {@link #BROWSER} or {@link #CLI}.
 *                   The screen's own rule forbids drawing a marker for it; what it is for is picking
 *                   the address the 退出 button posts to ({@code /web/sessions/{id}/revoke} or
 *                   {@code /web/cli-sign-ins/{id}/revoke})
 * @param deviceId   the handle the console revokes by. <strong>Null for a session created before
 *                   this version</strong>, which carried no stamp; such a row can be seen and
 *                   cannot be ended, and the console is what decides that. Never null for a CLI
 *                   login: it is the authorization's own id
 * @param address    where the login came from, or null when nothing was readable — for a CLI login
 *                   that is the console visitor who approved it (V16)
 * @param current    whether this is the session making the request — never its id, which does not
 *                   leave the server. Always false for a CLI login: the person reading the page is
 *                   on a browser, and the row is about a machine somewhere else
 */
record SessionResponse(
        String kind,
        String deviceId,
        String device,
        String address,
        String loggedInAt,
        String lastSeenAt,
        boolean current) {

    /**
     * A browser's own login. The name is fixed because this is a wire value the screen compares
     * against, not a label.
     */
    static final String BROWSER = "browser";

    /**
     * A CLI login: a machine that signed in through the login-request screen.
     *
     * <p>The name is fixed for the same reason, and the segment below is the design's: the second
     * line of such a row opens with the kind of device, and this one has no User-Agent to derive it
     * from (v4 Q6, design/README.md §登录设备).
     */
    static final String CLI = "cli";

    /** What a CLI login's second segment opens with, before the name the person typed. */
    private static final String CLI_DEVICE_PREFIX = "CLI · ";

    static SessionResponse ofBrowser(LoginDevice session) {
        return new SessionResponse(
                BROWSER,
                session.deviceId(),
                DeviceDescription.of(session.userAgent()),
                session.address(),
                text(session.loggedInAt()),
                text(session.lastSeenAt()),
                session.current());
    }

    static SessionResponse ofCli(CliSignIn.Login login) {
        return new SessionResponse(
                CLI,
                login.id(),
                CLI_DEVICE_PREFIX + login.name(),
                login.address(),
                text(login.signedInAt()),
                // Never null: the module answers the approval when nothing has been recorded since
                // (`CliSignIn.Login.lastUsedAt`), because a row with an empty 最近活动 is a state the
                // design does not draw.
                text(login.lastUsedAt()),
                false);
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }
}

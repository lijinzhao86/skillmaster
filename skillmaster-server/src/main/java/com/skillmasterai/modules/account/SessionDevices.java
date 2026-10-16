package com.skillmasterai.modules.account;

import com.skillmasterai.common.Ulid;
import jakarta.servlet.http.HttpSession;

/**
 * What a session remembers about the device it was created on (ADR 0043 §数据模型).
 *
 * <p><strong>Three session attributes and no table.</strong> Which machine a login came from is the
 * session's own fact, so it is stored in the session. A {@code login_device} table would have to key
 * on a session id or on the framework's primary key, which is a shadow index standing beside a store
 * only the framework may read — and then nothing would guarantee that a row disappeared when its
 * session did. As attributes they go with the session row, which V3's {@code spring_session_attributes}
 * already cascades on, so revoking a device needs no cleanup task and no sweep can be late.
 *
 * <p><strong>{@link #ID} is what a device is addressed by, and it is deliberately not the session
 * id.</strong> A session id is bearer-equivalent: whoever reads one is that person until it expires.
 * So the console names a device by a ULID that means nothing anywhere else, and a session id never
 * leaves the server — the list's contract says so and a test measures it (accepting #6). The two are
 * not interchangeable even here: this id is unique among <em>one account's</em> sessions, which is
 * as much as the console needs to address a row, and is deliberately nothing more.
 */
public final class SessionDevices {

    /**
     * The device's public name: a ULID.
     *
     * <p><strong>No unique index, and that is the shape rather than an omission.</strong> It lives in
     * a serialised attribute, not in a column, so enforcing uniqueness would mean adding a column —
     * which is the table this class exists to avoid. Collisions are what a ULID makes them: the
     * probability of two logins on one account landing on the same 80 random bits.
     */
    public static final String ID = "device_id";

    /** The User-Agent exactly as it arrived. Readable shape is the list's business (Q3). */
    public static final String USER_AGENT = "device_user_agent";

    /** Where the login came from: the most effective answer to "is this machine mine?" (Q4). */
    public static final String ADDRESS = "device_login_address";

    private SessionDevices() {
    }

    /**
     * Writes the three, once, at the login.
     *
     * <p>Called from {@code api.WebSession.establish}, which is where a session starts and the only
     * place that has the request a login arrived on. A parameter that arrives null is stored as
     * absent rather than as a null — {@code HttpSession.setAttribute} does that by contract — which
     * is the honest shape for a client that sent no User-Agent: the list says nothing rather than
     * saying "null".
     */
    public static void stamp(HttpSession session, String userAgent, String address) {
        session.setAttribute(ID, Ulid.generate());
        session.setAttribute(USER_AGENT, userAgent);
        session.setAttribute(ADDRESS, address);
    }
}

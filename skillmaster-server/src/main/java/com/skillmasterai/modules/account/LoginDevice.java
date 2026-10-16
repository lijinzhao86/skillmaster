package com.skillmasterai.modules.account;

import java.time.Instant;

/**
 * One login, as the console's list sees it (ADR 0043 §接口).
 *
 * <p><strong>Nothing here is a session id</strong>, and that is the type's main job: the framework
 * keys sessions by an id that is worth as much as the cookie carrying it, so the list addresses a
 * row by {@link #deviceId} instead. The two times are facts the framework already keeps — when the
 * session was created and when it was last touched — rather than anything this product writes.
 *
 * @param deviceId   {@link SessionDevices#ID}: the handle the console addresses. <strong>Null is
 *                   possible and is not a bug:</strong> a session created before this version
 *                   carried no stamp, so it has no name to be revoked by. It is still listed — a
 *                   login that cannot be seen is exactly the thing this version exists to end — and
 *                   the console is what decides that a row with no handle has no revoke button.
 *                   A deployment of this version cannot produce one: {@code WebSession.establish}
 *                   stamps every login, and a database that ran an earlier release has to be
 *                   rebuilt anyway (see the server README).
 * @param userAgent  the User-Agent as it arrived, or null when the client sent none
 * @param address    the login's source address, or null when the container could not resolve one
 * @param loggedInAt when this device signed in — the session's creation time, which the absolute
 *                   ceiling is measured from
 * @param lastSeenAt when it was last used. The idle ruler is measured from here, and it is what
 *                   makes an abandoned machine visible before it expires
 * @param current    whether this is the session making the request. <strong>A boolean rather than an
 *                   id</strong>, because "which one am I" is the only question the console asks of
 *                   it and answering with an id would put one in a response
 */
public record LoginDevice(String deviceId, String userAgent, String address, Instant loggedInAt,
        Instant lastSeenAt, boolean current) {
}

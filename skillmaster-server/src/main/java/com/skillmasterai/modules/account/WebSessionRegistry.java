package com.skillmasterai.modules.account;

import java.util.List;

/**
 * What M1 does with sessions it did not create.
 *
 * <p>Sessions belong to Spring Session, which means no code outside the framework may name its
 * tables in SQL. What M1 needs of them is four operations, and this is those as methods — so that
 * "reset the password" can mean "and nobody stays logged in with the old one", and "end that
 * machine's login" can mean the row is gone, without anyone writing a query against a table this
 * module does not read.
 *
 * <p><strong>Every method takes the user id, and the id is the principal name.</strong> That value
 * comes from the {@code SecurityContext} the login stored, which is why
 * {@code WebAuthentication.getName()} has to return it: get that wrong and these methods quietly
 * find nothing, which is the failure mode where a password reset looks like it worked (ADR 0014).
 * It is also what scopes all four to one account — a device id from somebody else's session matches
 * nothing here, and that is the answer the console's 404 is made of rather than a check bolted on
 * afterwards.
 *
 * <p><strong>The current session is excluded by id, and the comparison happens inside.</strong>
 * "Every device except this one" has to be defined against the session making the request, or the
 * person doing it ends their own login — which is why {@code revokeOthers} takes that session's id
 * rather than "all of them" and a subtraction. It stays a parameter rather than something read from
 * the session here because the framework's {@code Session} is not the servlet's {@code HttpSession}:
 * only the caller has both.
 */
public interface WebSessionRegistry {

    /** Ends every browser session belonging to this user. A no-op when there are none. */
    void revokeAllFor(String userId);

    /**
     * Every login this user has, most recently used first.
     *
     * <p>Newest first because the list answers "is one of these not me", and the machine somebody
     * does not recognise is far more likely to be one they just used than one they left weeks ago.
     *
     * @param currentSessionId the session making the request, marked in the result; null when the
     *                         caller has none, which marks nothing and is only reachable from code
     *                         that is not on a request
     */
    List<LoginDevice> listFor(String userId, String currentSessionId);

    /**
     * Ends one of this user's devices, addressed by {@link SessionDevices#ID}.
     *
     * @return false when this user has no session with that device id — which is the same answer
     *         whether it never existed or belongs to somebody else, because telling those apart
     *         would confirm that a device with that id exists (accepting #7)
     */
    boolean revoke(String userId, String deviceId);

    /**
     * Ends every one of this user's sessions except the one making the request.
     *
     * @return how many were ended, so that the console can say what happened rather than leaving the
     *         person to compare two lists. Zero is a normal answer: it means there was nothing else
     *         signed in
     */
    int revokeOthers(String userId, String currentSessionId);
}

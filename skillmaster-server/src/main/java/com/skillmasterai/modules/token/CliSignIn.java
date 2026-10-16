package com.skillmasterai.modules.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One CLI login, from the click that starts it to the row that ends it (v4).
 *
 * <p><strong>What this is not: a way to mint a credential from a credential.</strong> The only thing
 * that can start a login is a session, which is why {@link #start} takes a user id that the browser
 * plane has already established and why nothing here answers a request that carries a token instead.
 * ADR 0040 §决定 1 is the rule; this interface is what it looks like when written down.
 *
 * <p><strong>Two halves, and the split between them is the design.</strong> {@link #start} is the
 * consent: it records who agreed to what, on which machine, and hands the browser a one-time code to
 * carry back. {@link #exchange} is the delivery: the code, plus proof that the caller is the process
 * that asked for it, becomes the credential itself. Nothing travels in a URL except the code —
 * addresses end up in histories, referrers and logs, and a credential must not.
 *
 * <p>Strings rather than the platform's own types, like {@link TokenRevocation} beside it: an id here
 * is a string the database holds, and a module that invented a wrapper for it would be inventing one
 * for every caller too.
 */
public interface CliSignIn {

    /**
     * Records the consent, issues the one-time code, and says where to send the browser next.
     *
     * <p>It also ends the login this one replaces — the same machine asking again, recognised by the
     * name the person typed (v4 Q7). That the server has to use the name is a real limitation with a
     * real cost, and both are recorded where the name is stored.
     *
     * @param approvedFrom the address the approval came from, or null when the socket did not expose
     *                     one. It is a property of the request rather than of what was sent, which is
     *                     why it is a parameter here and not a field of {@link Start} — and it is kept
     *                     because the console's row shows it, and after this call nothing else holds it
     */
    Started start(String userId, Start request, String approvedFrom);

    /**
     * Spends a code and hands back the credential, once.
     *
     * <p>Empty covers four failures that are deliberately one answer: no such code, an expired one, a
     * spent one, and a verifier that did not match. See {@code CodeNotFoundException} for why they are
     * not told apart.
     *
     * @param callerAddress where the attempt came from, or null when there is no socket behind it.
     *                      This route is the one public write this server has, so it is the one that
     *                      carries a limit of its own — and the limit is keyed on this, never on the
     *                      code, because a limit that answered differently for a code that exists would
     *                      be a better existence probe than the status code is willing to be
     */
    Optional<Credential> exchange(String code, String codeVerifier, String callerAddress);

    /** Ends one login. False when it is not there, already over, or not this person's. */
    boolean revoke(String userId, String authorizationId);

    /**
     * Every CLI login this account still has, most recently active first.
     *
     * <p><strong>This is what the console's 「登录设备」 list reads to put a CLI login beside a browser
     * session</strong> (v4 Q6). The two rows are one kind of thing — a machine that may act as this
     * person, with the same two rulers and the same way out — so the screen shows them in one list,
     * and the merge happens above this interface, in the browser plane's own controller. A module
     * does not read another module's tables, and M1 (which owns the session list) knows nothing about
     * tokens.
     *
     * <p><strong>Live logins only.</strong> What bounds this list is the credential's own expiry, not
     * a sweep: the rows live in {@code oauth_authorization} and nothing deletes them when the
     * credential dies (the same thing v4 §数据模型 says about the code table). So a login whose
     * credential has run out — idle or past the ceiling, which is the same column — is not a device
     * anybody is signed in on, and listing it would make the screen's count wrong about the only
     * question it answers.
     *
     * @return newest activity first, which is the order the screen wants and the reason it does not
     *         sort what it is given
     */
    List<Login> listFor(String userId);

    /**
     * The other answer: nothing is minted, and the browser is sent back where it came from.
     *
     * <p>It exists because the refusal has to arrive at the CLI's loopback address and the page cannot
     * know it — so the "no" comes back through the server for the same reason the "yes" does. Nothing
     * is written: a refusal is a login that did not happen.
     *
     * @return the address to send the browser to, with {@code error=access_denied} on it, which is what
     *         tells the CLI the person declined rather than that something broke
     */
    String refuse(String clientId, String state);

    /**
     * @param name          what the person typed for this machine. Required, and it is the row's
     *                      identity on the console — {@code CLI · <name>}
     * @param clientId      which registered client this is for; it decides which row the scopes and
     *                      the redirect are read from
     * @param codeChallenge the PKCE challenge the caller will have to prove at exchange. It has to
     *                      arrive here rather than at {@link #exchange}, because the code is created
     *                      with it and the code is what the exchange is checked against
     * @param state         handed back with the code, so the caller can tell its own callback from
     *                      somebody else's
     * @param scopes        what the caller asked for. Intersected with what the client is registered
     *                      for, so a request can narrow but never widen
     */
    record Start(String name, String clientId, String codeChallenge, String state, Set<String> scopes) {
    }

    /** @param redirectTo where the browser goes next: the registered loopback address, with the code */
    record Started(String redirectTo) {
    }

    /**
     * The credential, and the one fact about the login beside it that its holder has no other way to
     * learn.
     *
     * @param name      what the person typed for this machine — the row's identity on the console
     *                  ({@code CLI · <name>}) and, being the only thing the server recognises a
     *                  machine by, what a repeated login replaces (Q7). It travels with the credential
     *                  because the delivery is the client's one moment of contact with the server: the
     *                  code it redeems is a digest of a value that has no name in it, and there is no
     *                  session on this route to ask anything else by (v4 §接口 ③)
     * @param token     the credential's value, which exists in this object and in no row
     * @param expiresAt when it dies if nothing else happens to it — the idle ruler, at issuance
     * @param scopes    what it may do: what the client is registered for, intersected with the request
     */
    record Credential(String name, String token, Instant expiresAt, Set<String> scopes) {
    }

    /**
     * One live CLI login, as the console's list sees it.
     *
     * <p><strong>{@link #id} is the authorization's id, and it is what the console addresses.</strong>
     * A CLI login has no session id to name it by — it is not a session — so the row the console ends
     * is the authorization row itself (v4 §接口, {@code POST /web/cli-sign-ins/{id}/revoke}).
     *
     * <p><strong>The name is what the person typed, and it travels as typed.</strong> The screen's
     * second segment is {@code CLI · <name>}, and the prefix belongs to the layer that turns stored
     * facts into what a screen shows rather than to this record: a module is not where a screen's
     * spelling lives.
     *
     * @param name        the machine's name, written only by this route — a row with one is a CLI
     *                    login and nothing else is (V15)
     * @param address     where the login was approved from, from the console visitor's socket, or null
     *                    when there was none to read. The screen draws the half it has (V16)
     * @param signedInAt  when the person agreed to it — the authorization row's own creation, which
     *                    is also what the absolute ceiling is measured from
     * @param lastUsedAt  the last use something recorded, <strong>or {@code signedInAt} when nothing
     *                    has been recorded yet</strong>. The column is sampled at a slide window
     *                    (V17), so a freshly approved login carries no value at all — and a row that
     *                    said nothing there would be a row with an empty 「最近活动」, which is not a
     *                    state the design draws. The approval <em>is</em> the last thing that happened
     *                    to such a login, and it is the same value the column would hold had the use
     *                    been recorded: the expiry a slide writes is already the one issuance wrote
     */
    record Login(String id, String name, String address, Instant signedInAt, Instant lastUsedAt) {
    }
}

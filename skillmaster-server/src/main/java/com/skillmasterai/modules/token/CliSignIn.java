package com.skillmasterai.modules.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One CLI login, from the question the CLI asks to the row that answers it (v68, ADR 0190).
 *
 * <p><strong>What this is not: a way to mint a credential from a credential.</strong> The only thing
 * that can start a login is a person in a browser, which is why {@link #approve} takes a user id the
 * browser plane has already established and why {@link #start} — which is anonymous — can only create
 * a short-lived question. ADR 0040 §决定 1 is the rule; this interface is what it looks like when
 * written down.
 *
 * <p><strong>Three moments, and the split between them is the design.</strong>
 *
 * <ul>
 *   <li>{@link #start} is the machine asking. It has no identity yet; what it gets back is a ticket
 *       for a link and a secret only it will hold.
 *   <li>{@link #approve} is the person answering, in a session. It writes the authorization row and
 *       nothing else — the credential does not exist yet.
 *   <li>{@link #poll} is the machine collecting. It proves possession of the secret and of the PKCE
 *       verifier, and only then is the credential minted and handed over once.
 * </ul>
 *
 * <p><strong>The ticket travels in a URL and must not be enough on its own.</strong> Links land in
 * chats, browser histories and server logs. The secret, the verifier and the approved authorization
 * row are the three things a leaked ticket does not have.
 */
public interface CliSignIn {

    /**
     * Creates the question: a ticket for the link, a secret for this process, and ten minutes.
     *
     * <p>Anonymous by necessity — the caller has no credential, which is what it is asking for. What
     * keeps that from being an open write is its shape: one short-lived row, no authorization, no
     * credential, and a rate limit on the address.
     *
     * @param callerAddress the address the request came from, or null when the socket did not expose
     *                      one. It is shown on the approval page and is the one fact about the machine
     *                      the CLI cannot make up
     */
    Started start(Start request, String callerAddress);

    /**
     * The public view of a question: what the page needs to draw it, and nothing that could redeem it.
     *
     * <p>Empty when no such ticket exists. A ticket that has expired, been denied or been used is
     * still a view — the page has a state for each — because "this link is no longer a question" is
     * an answer a person needs.
     */
    Optional<PublicView> view(String ticket, String callerAddress);

    /**
     * The person's yes: writes the authorization row, in the same transaction that marks the question
     * answered.
     *
     * <p>False when the question is gone, already answered, or past its expiry — the page shows the
     * same "no longer a question" state. The name is the person's final word for the machine; it is
     * what the console lists, and a repeated login on the same name ends the previous one (v4 Q7).
     *
     * @param approvedFrom the address the approval came from, or null when the socket did not expose
     *                     one. It is a property of the request rather than of what was sent, and after
     *                     this call nothing else holds it
     */
    boolean approve(String userId, String ticket, String name, String approvedFrom);

    /** The person's no: marks the question denied and writes nothing else. False when it was already gone. */
    boolean refuse(String ticket);

    /**
     * The machine giving up: marks a pending question denied, so the link stops being a question.
     *
     * <p><strong>Why the server has to hear about this at all.</strong> A person who pressed Ctrl-C
     * still has a link in their hand — or in a chat window. Leaving the row pending would let it be
     * approved afterwards, producing an authorization for a login its own machine already abandoned
     * (PRD 验收 #3). The secret is the same one the poll presents, so holding the link is not enough
     * to cancel somebody else's question.
     *
     * <p><strong>Idempotent, and quiet about what it cannot do.</strong> A question already denied,
     * used, or expired while never approved answers true without changing anything: the caller is
     * gone either way, and turning a cancellation into an error would hand a network failure to
     * somebody who has already stopped waiting. <strong>An approval that landed first is different</strong>
     * — it is marked denied and its authorization (which nobody will ever collect) is revoked.
     * False only when the ticket and secret do not match.
     */
    boolean cancel(String ticket, String deviceSecret);

    /**
     * The machine collecting: spends the question and hands back the credential, once.
     *
     * <p>Empty covers every way the caller can fail to be the process that asked — no such ticket, a
     * secret that does not match, a verifier that does not match — for the same reason the old
     * exchange gave one answer: telling them apart would make the endpoint an existence probe.
     *
     * <p>A result that is not empty carries a status and, only when approved, the credential. That is
     * how the caller tells "not yet" from "never" without a second request.
     *
     * @param callerAddress the address the poll came from; the per-address limit is keyed on it
     */
    Optional<Poll> poll(String ticket, String deviceSecret, String codeVerifier, String callerAddress);

    /** Ends one login. False when it is not there, already over, or not this person's. */
    boolean revoke(String userId, String authorizationId);

    /**
     * Ends the login the caller is holding, from the credential itself (v46, ADR 0142).
     *
     * <p><strong>The id comes from the token's own row, never from the request.</strong> That is what
     * makes this safe without an ownership check the controller could forget: a credential can only
     * end the authorization it was minted under. {@link #revoke} is still the console's route, where
     * the id is the thing being addressed.
     *
     * <p>A login that is already over is not an error here — the target state is reached. The
     * validator refuses such a token before this is called, so in practice this runs once; the
     * no-op is what keeps a replay from becoming a failure.
     */
    void revokeSelf(String userId, String authorizationId);

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
     * credential dies. So a login whose credential has run out — idle or past the ceiling, which is
     * the same column — is not a device anybody is signed in on, and listing it would make the
     * screen's count wrong about the only question it answers.
     *
     * @return newest activity first, which is the order the screen wants and the reason it does not
     *         sort what it is given
     */
    List<Login> listFor(String userId);

    /** The five answers a question can have. Spelled once, because both the page and the CLI read them. */
    String PENDING = "pending";
    String APPROVED = "approved";
    String DENIED = "denied";
    String EXPIRED = "expired";
    String USED = "used";

    /**
     * What the CLI asks for.
     *
     * @param name          what this machine is called, proposed by the CLI. Required and non-blank:
     *                      an unnamed row is one with nothing to recognise it by
     * @param clientId      which registered client this is for; it decides which scopes may be granted
     * @param codeChallenge the PKCE challenge the caller will have to prove at {@link #poll}
     * @param scopes        what the caller asked for. Intersected with what the client is registered
     *                      for, so a request can narrow but never widen
     */
    record Start(String name, String clientId, String codeChallenge, Set<String> scopes) {
    }

    /**
     * The two values the CLI keeps.
     *
     * @param ticket        goes in the link; the page and the poll both address the question by it
     * @param deviceSecret  never leaves the CLI; the poll proves possession of it
     * @param expiresInSeconds ten minutes, said in seconds because that is what the CLI schedules by
     * @param intervalSeconds the poll interval the CLI should start at
     */
    record Started(String ticket, String deviceSecret, long expiresInSeconds, long intervalSeconds) {
    }

    /**
     * The page's view. No ticket, no secret, no authorization id — anything that could redeem the
     * question must not be in the body a browser receives.
     *
     * @param status  one of the five constants above
     * @param name    the machine name the CLI proposed, for the editable field
     * @param client  the client id; the page turns it into a friendly name and warns on unknowns
     * @param scopes  what the approval would grant
     * @param from    where the request came from, or null when the socket did not expose it
     */
    record PublicView(String status, String name, String client, Set<String> scopes, String from,
            Instant createdAt, Instant expiresAt, long ageMinutes) {
    }

    /**
     * One poll's answer.
     *
     * @param status     one of the five constants above
     * @param credential present only when {@code status} is {@link #APPROVED}
     */
    record Poll(String status, Credential credential) {
    }

    /**
     * The credential, and the one fact about the login beside it that its holder has no other way to
     * learn.
     *
     * @param userId    whose login this is. The terminal prints the handle beside the credential
     *                  (v46 验收 A3), and this is the last moment the poll knows both: it has the
     *                  authorization row, and the caller has no session to ask anything by
     * @param name      what the person typed for this machine — the row's identity on the console
     *                  ({@code CLI · <name>}). It travels with the credential because the delivery is
     *                  the client's one moment of contact with the server
     * @param token     the credential's value, which exists in this object and in no row
     * @param expiresAt when it dies if nothing else happens to it — the idle ruler, at issuance
     * @param scopes    what it may do: what the client is registered for, intersected with the request
     */
    record Credential(String userId, String name, String token, Instant expiresAt, Set<String> scopes) {
    }

    /**
     * One live CLI login, as the console's list sees it.
     *
     * <p><strong>{@link #id} is the authorization's id, and it is what the console addresses.</strong>
     * A CLI login has no session id to name it by — it is not a session — so the row the console ends
     * is the authorization row itself.
     *
     * @param name        the machine's name, written only by an approval — a row with one is a CLI
     *                    login and nothing else is (V15)
     * @param address     where the login was approved from, from the console visitor's socket, or null
     *                    when there was none to read
     * @param signedInAt  when the person agreed to it — the authorization row's own creation, which
     *                    is also what the absolute ceiling is measured from
     * @param lastUsedAt  the last use something recorded, or {@code signedInAt} when nothing has been
     *                    recorded yet
     */
    record Login(String id, String name, String address, Instant signedInAt, Instant lastUsedAt) {
    }
}

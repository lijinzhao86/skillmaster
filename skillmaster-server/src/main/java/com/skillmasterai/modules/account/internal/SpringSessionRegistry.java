package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.LoginDevice;
import com.skillmasterai.modules.account.SessionDevices;
import com.skillmasterai.modules.account.WebSessionRegistry;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Reads and ends sessions through Spring Session's own API, never through its tables.
 *
 * <p>M1 owns {@code spring_session} in {@code ModuleMap} because a table in a migration has to have
 * an owner (see {@code TableOwnershipTest}), but owning it is not the same as writing to it: the
 * framework does that, and a hand-written {@code DELETE FROM spring_session} here would be a second
 * implementation of a schema this module does not control.
 *
 * <p><strong>The lookup is by principal name, and the principal name is the user id.</strong> That
 * value comes from the {@code SecurityContext} the login stored, which is why
 * {@code WebAuthentication.getName()} has to return the id: get that wrong and every method here
 * silently finds nothing, which is the failure mode where a password reset looks like it worked.
 *
 * <p><strong>{@code findByPrincipalName} materialises the account's sessions into a map, and the
 * deletes mutate the store that map came from.</strong> Every method below therefore copies out what
 * it is going to work on before it starts deleting — the same care {@code revokeAllFor} has always
 * taken, and the reason none of these stream straight from the map into {@code deleteById}.
 *
 * <p><strong>Revoking records what happened, in M10's log, from here.</strong> The precedent is
 * {@code ApiKeyRegistry}, which writes {@code api_key_revoke} where the revocation happens rather
 * than in a use case above it: the row and the trace are then written by the same code, in one
 * place, and a new caller cannot forget the second half. What it is <em>not</em> is atomic with the
 * delete — the session store commits its own writes — which is a difference from the key path and
 * is why the event is written after the delete rather than before: a trace of something that did not
 * happen is worse than a delete with no trace, and this way round the delete is what is believed.
 */
public final class SpringSessionRegistry implements WebSessionRegistry {

    /**
     * What the audit row calls this. Named for the act rather than for the button (the console says
     * 退出): the log is read by people asking "what happened to that login", and every other action
     * in it is a verb in the same form — {@code api_key_revoke}, {@code token_revoke}.
     */
    private static final String REVOKE_ACTION = "session_revoke";

    private static final String TARGET_TYPE = "session";

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final AuditLog audit;

    public SpringSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions,
            AuditLog audit) {
        this.sessions = sessions;
        this.audit = audit;
    }

    @Override
    public void revokeAllFor(String userId) {
        // Copied before deleting: the map came from the store the deletes are mutating.
        List<String> ids = List.copyOf(sessions.findByPrincipalName(userId).keySet());
        ids.forEach(sessions::deleteById);
    }

    @Override
    public List<LoginDevice> listFor(String userId, String currentSessionId) {
        return sessions.findByPrincipalName(userId).values().stream()
                .map(session -> toDevice(session, currentSessionId))
                .sorted(Comparator.comparing(LoginDevice::lastSeenAt).reversed())
                .toList();
    }

    @Override
    public boolean revoke(String userId, String deviceId) {
        List<? extends Session> matches = sessions.findByPrincipalName(userId).values().stream()
                .filter(session -> deviceId.equals(session.getAttribute(SessionDevices.ID)))
                .toList();
        if (matches.isEmpty()) {
            return false;
        }
        for (Session session : matches) {
            sessions.deleteById(session.getId());
            audit.record(AuditEvent.of(userId, REVOKE_ACTION, TARGET_TYPE, deviceId));
        }
        // More than one is not possible — the ids are ULIDs and this account issued them — but the
        // loop does not depend on that being true, and a duplicate would be a bug worth ending
        // rather than one worth asserting about.
        return true;
    }

    @Override
    public int revokeOthers(String userId, String currentSessionId) {
        List<? extends Session> others = sessions.findByPrincipalName(userId).values().stream()
                .filter(session -> !session.getId().equals(currentSessionId))
                .toList();
        for (Session session : others) {
            sessions.deleteById(session.getId());
            // The device id rather than the session id, for the reason the list has none either: a
            // trace that carries a credential is a trace that has to be protected like one. A
            // session from before this version has no device id, and null is the honest target.
            audit.record(new AuditEvent(userId, REVOKE_ACTION, TARGET_TYPE,
                    session.getAttribute(SessionDevices.ID), Map.of("via", "revoke-others")));
        }
        return others.size();
    }

    private static LoginDevice toDevice(Session session, String currentSessionId) {
        return new LoginDevice(
                session.getAttribute(SessionDevices.ID),
                session.getAttribute(SessionDevices.USER_AGENT),
                session.getAttribute(SessionDevices.ADDRESS),
                session.getCreationTime(),
                session.getLastAccessedTime(),
                session.getId().equals(currentSessionId));
    }
}

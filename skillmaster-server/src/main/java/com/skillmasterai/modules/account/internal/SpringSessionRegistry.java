package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.WebSessionRegistry;
import java.util.List;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Ends sessions through Spring Session's own API, never through its tables.
 *
 * <p>M1 owns {@code spring_session} in {@code ModuleMap} because a table in a migration has to have
 * an owner (see {@code TableOwnershipTest}), but owning it is not the same as writing to it: the
 * framework does that, and a hand-written {@code DELETE FROM spring_session} here would be a second
 * implementation of a schema this module does not control.
 *
 * <p><strong>The lookup is by principal name, and the principal name is the user id.</strong> That
 * value comes from the {@code SecurityContext} the login stored, which is why
 * {@code WebAuthentication.getName()} has to return the id: get that wrong and this method
 * silently revokes nothing, which is the failure mode where a password reset looks like it worked.
 */
public final class SpringSessionRegistry implements WebSessionRegistry {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SpringSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public void revokeAllFor(String userId) {
        // Copied before deleting: the map came from the store the deletes are mutating.
        List<String> ids = List.copyOf(sessions.findByPrincipalName(userId).keySet());
        ids.forEach(sessions::deleteById);
    }
}

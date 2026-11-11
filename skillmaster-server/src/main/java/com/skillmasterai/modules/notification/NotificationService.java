package com.skillmasterai.modules.notification;

import com.skillmasterai.modules.notification.internal.NotificationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * M14's public seam: write a notification, close one, read a recipient's feed.
 *
 * <p><strong>It knows nothing about invitations, requests or skills.</strong> The caller resolves
 * recipients, decides the kind and supplies the subject key; this class only stores the event and
 * the per-recipient read state. That is what makes it a bottom-layer capability rather than a
 * feature of sharing: a new business flow constructs a {@link NotificationDraft} and needs no
 * change here.
 *
 * <p><strong>No outbox.</strong> A notification is a row in the same database as the business
 * write, so the two commit together (ADR 0139 §理由). External channels, when they arrive, add an
 * outbox beside this table rather than changing its meaning.
 */
public final class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /** Writes the event and every recipient row; an open notification of the same kind and subject
     *  is moved rather than duplicated. */
    public void emit(NotificationDraft draft, String at) {
        String id = repository.write(draft, at);
        repository.addRecipients(id, draft.recipients(), at);
        // A moved notification is a new event for the person reading it: they may have read the
        // previous version before the role or expiry changed.
        repository.clearRead(id);
    }

    /**
     * Makes the open 下线中 notice for this skill stop being shown (v61).
     *
     * <p>Expiry, not deletion: the row stays for history and only leaves the feed. Cancelling a
     * ramp and completing it both call this — the completion is followed by its own announcement,
     * so what disappears is the advance notice, not the record that the skill was taken down.
     *
     * @return how many notices were shortened
     */
    public int expireOfflineScheduled(String skillId, String at) {
        return repository.expireOfflineScheduled(skillId, at);
    }

    /** Records how a request-shaped notification ended. A no-op when it was already resolved. */
    public void resolve(String subjectKey, String kind, NotificationOutcome outcome, String at) {
        repository.resolve(subjectKey, kind, outcome.wireName(), at);
    }

    /**
     * Removes this person's place in everyone's feeds (v56's account purge).
     *
     * <p>Only the recipient rows are deleted: a notification is a shared event, and other people
     * who received the same one keep theirs. The person's own feed, read state and any invitation
     * addressed to them go with the rows.
     *
     * @return how many recipient rows were removed
     */
    public int deleteAllFor(String userId) {
        return repository.deleteAllFor(userId);
    }

    /**
     * Removes the stored handle from events this person caused, keeping the events (v56).
     *
     * <p>Another recipient may still hold the row; what must not survive is the handle, which is
     * about to become registrable by somebody else.
     *
     * @return how many events no longer name them
     */
    public int anonymizeActor(String userId) {
        return repository.anonymizeActor(userId);
    }

    public Optional<Notification> findFor(String id, String userId,
            Collection<String> administeredSkillIds) {
        return repository.findFor(id, userId, administeredSkillIds);
    }

    public NotificationPage page(NotificationQuery query) {
        List<Notification> items = repository.page(query);
        long total = repository.total(query);
        long unread = repository.unread(query.userId(), query.administeredSkillIds());
        return new NotificationPage(items, total, unread);
    }

    public long unread(String userId, Collection<String> administeredSkillIds) {
        return repository.unread(userId, administeredSkillIds);
    }

    /** @return false when the notification is not this recipient's, or is not visible to them */
    public boolean markRead(String id, String userId, Collection<String> administeredSkillIds,
            String at) {
        return repository.markRead(id, userId, administeredSkillIds, at);
    }

    public int markAllRead(String userId, Collection<String> administeredSkillIds, String at) {
        return repository.markAllRead(userId, administeredSkillIds, at);
    }
}

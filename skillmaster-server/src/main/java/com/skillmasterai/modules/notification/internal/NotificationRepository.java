package com.skillmasterai.modules.notification.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.notification.Notification;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationQuery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes M14's two tables.
 *
 * <p>The visibility clause is the one piece of business shape in here, and it is deliberately
 * narrow: an {@code access_request} row is shown only while its recipient still administers the
 * skill. The set of administered skill ids is computed by the use case, because M14 may not read
 * M12's tables.
 */
public final class NotificationRepository {

    private static final String COLUMNS = """
            n.id, n.kind, n.subject_key, n.actor_id, n.actor_handle, n.skill_id, n.role,
            n.outcome, n.expires_at, n.title, n.body, n.created_at, n.resolved_at,
            (r.read_at IS NULL) AS unread
            """;

    private static final String VISIBLE = """
            (n.kind <> 'access_request' OR n.skill_id = ANY(:adminSkillIds))
            """;

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the notification, or moves the one still open for this subject.
     *
     * <p>The partial unique index is what makes "one open notification per business object" true:
     * re-sharing updates the row already waiting, while a notification resolved earlier stays as
     * history and a new one is created after it.
     */
    public String write(NotificationDraft draft, String at) {
        if (draft.subjectKey() == null) {
            return jdbc.sql("""
                    INSERT INTO notification (id, kind, subject_key, actor_id, actor_handle,
                                              skill_id, role, expires_at, title, body, created_at)
                    VALUES (:id, :kind, NULL, :actorId, :actorHandle, :skillId, :role,
                            :expiresAt, :title, :body, :at)
                    RETURNING id
                    """)
                    .param("id", Ulid.generate())
                    .param("kind", draft.kind())
                    .param("actorId", draft.actorId())
                    .param("actorHandle", draft.actorHandle())
                    .param("skillId", draft.skillId())
                    .param("role", draft.role())
                    .param("expiresAt", draft.expiresAt())
                    .param("title", draft.title())
                    .param("body", draft.body())
                    .param("at", at)
                    .query(String.class)
                    .single();
        }
        return jdbc.sql("""
                INSERT INTO notification (id, kind, subject_key, actor_id, actor_handle,
                                          skill_id, role, expires_at, title, body, created_at)
                VALUES (:id, :kind, :subjectKey, :actorId, :actorHandle, :skillId, :role,
                        :expiresAt, :title, :body, :at)
                ON CONFLICT (subject_key, kind)
                    WHERE resolved_at IS NULL AND subject_key IS NOT NULL
                DO UPDATE SET actor_id     = EXCLUDED.actor_id,
                              actor_handle = EXCLUDED.actor_handle,
                              skill_id     = EXCLUDED.skill_id,
                              role         = EXCLUDED.role,
                              expires_at   = EXCLUDED.expires_at,
                              title        = EXCLUDED.title,
                              body         = EXCLUDED.body,
                              created_at   = EXCLUDED.created_at
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("kind", draft.kind())
                .param("subjectKey", draft.subjectKey())
                .param("actorId", draft.actorId())
                .param("actorHandle", draft.actorHandle())
                .param("skillId", draft.skillId())
                .param("role", draft.role())
                .param("expiresAt", draft.expiresAt())
                .param("title", draft.title())
                .param("body", draft.body())
                .param("at", at)
                .query(String.class)
                .single();
    }

    public void addRecipients(String notificationId, Collection<String> userIds, String at) {
        for (String userId : userIds) {
            jdbc.sql("""
                    INSERT INTO notification_recipient (notification_id, user_id, created_at)
                    VALUES (:notificationId, :userId, :at)
                    ON CONFLICT (notification_id, user_id) DO NOTHING
                    """)
                    .param("notificationId", notificationId)
                    .param("userId", userId)
                    .param("at", at)
                    .update();
        }
    }

    /**
     * Removes this person from every feed (v56's account purge).
     *
     * <p>Only the recipient rows: the notification itself is shared, and other recipients keep it.
     * An orphaned event — nobody left to show it to — is harmless; it is not a queue.
     */
    public int deleteAllFor(String userId) {
        return jdbc.sql("DELETE FROM notification_recipient WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /**
     * Makes the event unread for every recipient again.
     *
     * <p>Re-sharing or re-requesting moves the one open row rather than adding a second, and the
     * person who already read the old one has not seen this one. The read state belongs to the
     * event as it now stands, so it is cleared when the event is rewritten.
     */
    public void clearRead(String notificationId) {
        jdbc.sql("UPDATE notification_recipient SET read_at = NULL"
                        + " WHERE notification_id = :id")
                .param("id", notificationId)
                .update();
    }

    /** Marks every still-open notification for this subject with its result. Idempotent. */
    public void resolve(String subjectKey, String kind, String outcome, String at) {
        jdbc.sql("""
                UPDATE notification
                   SET outcome = :outcome, resolved_at = :at
                 WHERE subject_key = :subjectKey AND kind = :kind AND resolved_at IS NULL
                """)
                .param("subjectKey", subjectKey)
                .param("kind", kind)
                .param("outcome", outcome)
                .param("at", at)
                .update();
    }

    public Optional<Notification> findFor(String id, String userId,
            Collection<String> administeredSkillIds) {
        return jdbc.sql("""
                SELECT %s
                  FROM notification n
                  JOIN notification_recipient r ON r.notification_id = n.id
                 WHERE n.id = :id AND r.user_id = :userId
                   AND %s
                """.formatted(COLUMNS, VISIBLE))
                .param("id", id)
                .param("userId", userId)
                .param("adminSkillIds", administeredSkillIds.toArray(String[]::new))
                .query(NotificationRepository::map)
                .optional();
    }

    public List<Notification> page(NotificationQuery query) {
        String where = where(query);
        // long: `p=2147483647` with `per=50` overflows an int into a negative OFFSET, which
        // PostgreSQL rejects as a 500 instead of answering an empty page.
        long offset = ((long) query.page() - 1) * query.per();
        return jdbc.sql("""
                SELECT %s
                  FROM notification n
                  JOIN notification_recipient r ON r.notification_id = n.id
                 WHERE r.user_id = :userId AND %s
                 ORDER BY n.created_at DESC, n.id DESC
                 LIMIT :limit OFFSET :offset
                """.formatted(COLUMNS, where))
                .param("userId", query.userId())
                .param("adminSkillIds", query.administeredSkillIds().toArray(String[]::new))
                .param("now", query.now())
                .param("limit", query.per())
                .param("offset", offset)
                .query(NotificationRepository::map)
                .list();
    }

    public long total(NotificationQuery query) {
        String where = where(query);
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM notification n
                  JOIN notification_recipient r ON r.notification_id = n.id
                 WHERE r.user_id = :userId AND %s
                """.formatted(where))
                .param("userId", query.userId())
                .param("adminSkillIds", query.administeredSkillIds().toArray(String[]::new))
                .param("now", query.now())
                .query(Long.class)
                .single();
    }

    /**
     * The unread count is always the whole visible inbox, whatever the page filter is. That is what
     * makes the bell's number and {@code list --unread} agree.
     */
    public long unread(String userId, Collection<String> administeredSkillIds) {
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM notification n
                  JOIN notification_recipient r ON r.notification_id = n.id
                 WHERE r.user_id = :userId AND r.read_at IS NULL AND %s
                """.formatted(VISIBLE))
                .param("userId", userId)
                .param("adminSkillIds", administeredSkillIds.toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    public boolean markRead(String id, String userId, Collection<String> administeredSkillIds,
            String at) {
        // **Idempotent, so the predicate is not `read_at IS NULL`.** The row is the answer; marking
        // an already-read notification again is the state the caller asked for, not a 404.
        // `COALESCE` keeps the first read time rather than moving it forward on every call.
        return jdbc.sql("""
                UPDATE notification_recipient r
                   SET read_at = COALESCE(r.read_at, :at)
                  FROM notification n
                 WHERE r.notification_id = n.id AND r.user_id = :userId
                   AND n.id = :id AND %s
                """.formatted(VISIBLE))
                .param("id", id)
                .param("userId", userId)
                .param("at", at)
                .param("adminSkillIds", administeredSkillIds.toArray(String[]::new))
                .update() > 0;
    }

    /**
     * Drops the stored handle from events this person caused (v56's account purge).
     *
     * <p>The rows themselves stay while another recipient still has them — they are shared events.
     * What must not stay is the freed handle: after the purge somebody else may register it, and an
     * old row still naming it would attribute this person's action to the new holder.
     */
    public int anonymizeActor(String userId) {
        return jdbc.sql("UPDATE notification SET actor_handle = NULL WHERE actor_id = :id")
                .param("id", userId)
                .update();
    }

    /** @return how many rows were newly marked; already-read rows are not counted again */
    public int markAllRead(String userId, Collection<String> administeredSkillIds, String at) {
        return jdbc.sql("""
                UPDATE notification_recipient r
                   SET read_at = :at
                  FROM notification n
                 WHERE r.notification_id = n.id AND r.user_id = :userId
                   AND r.read_at IS NULL AND %s
                """.formatted(VISIBLE))
                .param("userId", userId)
                .param("at", at)
                .param("adminSkillIds", administeredSkillIds.toArray(String[]::new))
                .update();
    }

    private static String where(NotificationQuery query) {
        String status = switch (NotificationQuery.Status.fromWire(query.status())) {
            case ATTENTION -> """
                    ((n.kind IN ('share_invitation', 'access_request')
                      AND n.resolved_at IS NULL
                      AND (n.expires_at IS NULL OR n.expires_at > :now))
                     OR r.read_at IS NULL)
                    """;
            case PENDING -> """
                    (n.kind IN ('share_invitation', 'access_request')
                     AND n.resolved_at IS NULL
                     AND (n.expires_at IS NULL OR n.expires_at > :now))
                    """;
            case ALL -> "TRUE";
        };
        String visible = VISIBLE;
        String unread = query.unreadOnly() ? "AND r.read_at IS NULL" : "";
        return status + " AND " + visible + " " + unread;
    }

    private static Notification map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Notification(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("subject_key"),
                rs.getString("actor_id"),
                rs.getString("actor_handle"),
                rs.getString("skill_id"),
                rs.getString("role"),
                rs.getString("outcome"),
                rs.getString("expires_at"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("created_at"),
                rs.getString("resolved_at"),
                // `unread` is the query's column; `read` is its negation. Naming the column after
                // the question the nullable read_at answers keeps the SQL readable, and this is the
                // one place the two words meet.
                !rs.getBoolean("unread"));
    }
}

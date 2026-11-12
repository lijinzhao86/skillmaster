package com.skillmasterai.usecase;

import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The notices a service lifecycle emits (v61, ADR 0184): the ramp's advance notice, its
 * cancellation or completion, and the deletion that may follow.
 *
 * <p><strong>Written here rather than in three use cases, because the audience is the same
 * question each time.</strong> Recipients are grantees plus library joiners minus the owner, and
 * that set is read from M12 once per notice. Keeping it in one place means the ramp, the job and
 * the delete cannot drift into notifying different people about the same skill.
 *
 * <p><strong>Every call runs inside the caller's transaction.</strong> No method opens one: a
 * notice that survived the rollback of the state change it describes would be evidence of
 * something that never happened (ADR 0139's rule for the whole module).
 *
 * <p><strong>The ramp notice expires on its deadline, not when it is read.</strong> M14 filters an
 * expired row out of the feed, so the预告 disappears exactly when the skill stops serving — and a
 * cancelled ramp shortens it to now, which is what makes 取消下线 remove the预告.
 */
@Component
public class SkillLifecycleNotices {

    private final SkillMetadataService metadata;
    private final NotificationService notifications;

    public SkillLifecycleNotices(SkillMetadataService metadata,
            NotificationService notifications) {
        this.metadata = metadata;
        this.notifications = notifications;
    }

    /**
     * The advance notice: the skill serves for {@code RAMP_DAYS} more and cannot be joined.
     *
     * <p>The deadline rides on {@code expiresAt} rather than a new column: for this kind, "when
     * the notice stops being true" and "when the skill stops serving" are the same instant, and
     * M14 already gives that field its feed semantics. The client renders the clock row and reads
     * the date from the field it already receives.
     */
    void scheduled(String skillId, String ownerUserId, String ownerHandle, String dueAt,
            String now) {
        List<String> recipients = metadata.lifecycleRecipients(skillId, ownerUserId);
        if (recipients.isEmpty()) {
            return;
        }
        notifications.emit(new NotificationDraft(NotificationKinds.SKILL_OFFLINE_SCHEDULED,
                // Subject-keyed so a re-scheduled ramp is its own event rather than an edit of
                // one whose deadline has moved — the date is what the reader acts on.
                "offline:" + skillId + ":" + dueAt, ownerUserId, ownerHandle, skillId, null,
                null, null, dueAt, recipients), now);
    }

    /** The owner withdrew the ramp: the预告 stops being shown and the audience is told. */
    void cancelled(String skillId, String ownerUserId, String ownerHandle, String address,
            String now) {
        notifications.expireOfflineScheduled(skillId, now);
        send(skillId, ownerUserId, ownerHandle, "下线已取消",
                address + " 于 " + day(now) + " 取消了「下线中」，继续提供服务。", now);
    }

    /** The ramp's date arrived and the job materialised it. */
    void completed(String skillId, String ownerUserId, String ownerHandle, String address,
            String at) {
        notifications.expireOfflineScheduled(skillId, at);
        // **No copy advice here.** Copy reads the consumption plane, which this same transaction
        // has just switched off; the ramp's advance notice is where the way out belonged.
        send(skillId, ownerUserId, ownerHandle, "skill 已下线",
                address + " 已于 " + day(at) + " 下线，不再提供服务。", at);
    }

    /** The skill was deleted after the ramp, or without needing one. */
    void deleted(String skillId, String ownerUserId, String ownerHandle, String address,
            String at) {
        notifications.expireOfflineScheduled(skillId, at);
        send(skillId, ownerUserId, ownerHandle, "skill 已删除",
                address + " 已于 " + day(at) + " 被作者删除，不再提供服务。", at);
    }

    /** The date part of an RFC3339 instant — a notice says the day, not the millisecond. */
    private static String day(String rfc3339) {
        return rfc3339.length() >= 10 ? rfc3339.substring(0, 10) : rfc3339;
    }

    /**
     * An announcement: nothing to accept or decline, and by M14's shape it names neither an actor
     * nor a skill — the body carries the address instead. That is the same constraint the account
     * deletion's notice lives under (V34's {@code notification_announcement_shaped}).
     */
    private void send(String skillId, String ownerUserId, String ownerHandle, String title,
            String body, String at) {
        List<String> recipients = metadata.lifecycleRecipients(skillId, ownerUserId);
        if (recipients.isEmpty()) {
            return;
        }
        notifications.emit(new NotificationDraft(NotificationKinds.ANNOUNCEMENT, null,
                null, null, null, null, title, body, null, recipients), at);
    }
}

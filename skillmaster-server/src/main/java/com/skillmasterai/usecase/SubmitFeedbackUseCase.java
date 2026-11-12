package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.feedback.Feedback;
import com.skillmasterai.modules.feedback.FeedbackSafety;
import com.skillmasterai.modules.feedback.FeedbackService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One feedback document: validate it, apply the throttle, store it, tell the submitter, and leave
 * an audit row (v63).
 *
 * <p><strong>The consent version is the server's, not the client's.</strong> The client sends the
 * version it displayed; the submission is refused if that is not the version this deployment
 * serves. What is stored is always {@code legal.privacy()} — a client cannot pin an old clause.
 *
 * <p><strong>The scan runs before the throttle.</strong> A document that would be refused for
 * carrying personal data must not first spend one of the person's hourly slots; the order is also
 * the order the person experiences: fix the content, then wait.
 */
@Component
public class SubmitFeedbackUseCase {

    static final int MAX_PER_HOUR = 10;

    /** The only diagnostic keys a client may send; anything else is dropped, not refused. */
    static final Set<String> DIAGNOSTIC_KEYS = Set.of(
            "cli_version", "os", "arch", "at", "command", "error_code");

    private static final int MAX_DIAGNOSTIC_VALUE_LENGTH = 256;

    private final FeedbackService feedback;
    private final LegalTextVersions legal;
    private final NotificationService notifications;
    private final AuditLog audit;

    public SubmitFeedbackUseCase(FeedbackService feedback, LegalTextVersions legal,
            NotificationService notifications, AuditLog audit) {
        this.feedback = feedback;
        this.legal = legal;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public Result submit(String content, String skillAddress, Map<String, Object> diagnostics,
            String consentVersion, boolean allowSensitive, AuthenticatedSubject subject) {
        // **Kept verbatim.** A trailing newline is part of the document a person wrote, and the
        // receipt is supposed to name what was submitted; trimming it would make the stored copy
        // differ from the file for no gain. Blankness is checked without rewriting it.
        String text = content == null ? "" : content;
        if (text.isBlank()) {
            throw new RequestException(ErrorCode.FEEDBACK_EMPTY, "反馈内容为空。");
        }
        // PostgreSQL TEXT cannot hold U+0000, and rejecting it here answers with a readable 400
        // instead of a driver exception on the INSERT.
        if (text.indexOf('\u0000') >= 0) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "反馈内容含有无法保存的空字符（U+0000）；请用文本编辑器打开后重新提交。");
        }
        int points = FeedbackSafety.codePoints(text);
        if (points > FeedbackSafety.MAX_CODE_POINTS) {
            throw new RequestException(ErrorCode.FEEDBACK_TOO_LONG,
                    "反馈内容超过 " + FeedbackSafety.MAX_CODE_POINTS + " 个字符（实际 " + points + "）。",
                    List.of(ApiError.Detail.codePoints("content", "too_long",
                            points, FeedbackSafety.MAX_CODE_POINTS)));
        }
        if (!legal.privacy().equals(consentVersion)) {
            throw new RequestException(ErrorCode.FEEDBACK_CONSENT_STALE,
                    "同意说明已更新，请重新预览后再提交。");
        }
        // **A key prefix is replaced, not confirmed** (v63 做 #13): a client that did not redact
        // first still gets its document stored, with the key replaced below. The three personal-data
        // categories are the ones the person has to confirm.
        FeedbackSafety.Scan scan = FeedbackSafety.scan(text);
        if (needsConfirmation(scan) && !allowSensitive) {
            throw new RequestException(ErrorCode.FEEDBACK_SENSITIVE,
                    "内容可能包含个人信息；确认要发送请加 --allow-sensitive。",
                    scan.counts().entrySet().stream()
                            .filter(entry -> !"secret_prefix".equals(entry.getKey()))
                            .map(entry -> new ApiError.Detail("content",
                                    entry.getKey() + ":" + entry.getValue()))
                            .toList());
        }

        String at = Timestamps.now();
        String since = Timestamps.format(Instant.parse(at).minus(1, ChronoUnit.HOURS));
        if (feedback.countSince(subject.userId(), since) >= MAX_PER_HOUR) {
            String retryAt = feedback.oldestSince(subject.userId(), since)
                    .map(oldest -> Timestamps.format(
                            Instant.parse(oldest).plus(1, ChronoUnit.HOURS)))
                    .orElse(Timestamps.format(Instant.parse(at).plus(1, ChronoUnit.HOURS)));
            throw new RequestException(ErrorCode.FEEDBACK_THROTTLED,
                    "1 小时内最多提交 " + MAX_PER_HOUR + " 条反馈；请在 " + retryAt + " 之后重试。");
        }

        String address = skillAddress == null ? null : FeedbackSafety.redactSecrets(skillAddress);
        Feedback stored = feedback.create(subject.userId(), address, text,
                legal.privacy(), whitelist(diagnostics), at);
        notifications.emit(new NotificationDraft(
                NotificationKinds.ANNOUNCEMENT, null, null, null, null, null,
                "反馈已收到：" + stored.receipt(),
                "你提交的反馈已收到。要撤回并删除，运行 skmr feedback delete "
                        + stored.receipt() + " --yes。",
                null, List.of(subject.userId())), at);
        audit.record(new AuditEvent(subject.userId(), "feedback_submit", "feedback", stored.id(),
                Map.of("receipt", stored.receipt(),
                        "skillAddress", address == null ? "" : address)));
        return new Result(stored.receipt(), stored.createdAt());
    }

    private Map<String, Object> whitelist(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : DIAGNOSTIC_KEYS) {
            Object value = diagnostics.get(key);
            if (value instanceof String text && !text.isBlank()
                    && text.length() <= MAX_DIAGNOSTIC_VALUE_LENGTH) {
                String stored = FeedbackSafety.redactSecrets(text);
                kept.put(key, "command".equals(key) ? firstWord(stored) : stored);
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    /** The command name without its arguments. */
    private static String firstWord(String text) {
        return text.trim().split("\\s+", 2)[0];
    }

    /** The categories that need a second confirmation; a secret is replaced instead. */
    private static boolean needsConfirmation(FeedbackSafety.Scan scan) {
        return scan.counts().keySet().stream().anyMatch(key -> !"secret_prefix".equals(key));
    }

    /** @param receipt what the CLI prints and the delete command takes */
    public record Result(String receipt, String createdAt) {
    }
}

package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.Feedback;
import com.skillmasterai.modules.feedback.FeedbackAttachmentDraft;
import com.skillmasterai.modules.feedback.FeedbackAttachmentSafety;
import com.skillmasterai.modules.feedback.FeedbackSafety;
import com.skillmasterai.modules.feedback.FeedbackService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One feedback document and its attachments: validate them, apply the throttle, upload the bytes,
 * store the rows, tell the submitter, and leave an audit row (v64, attachments in v65).
 *
 * <p><strong>The upload happens outside the database transaction.</strong> Object storage cannot
 * join one; a transaction held open while a hundred mebibytes travel is a transaction that holds
 * a connection and locks a row for seconds. The order is: validate → upload → one transaction for
 * the feedback row, attachment rows, notification and audit. A failed transaction deletes the
 * objects that were already uploaded; a failed upload deletes the ones before it.
 *
 * <p><strong>The consent version is the server's, not the client's.</strong> The client sends the
 * version it displayed; the submission is refused if that is not the version this deployment
 * serves. What is stored is always {@code legal.privacy()} — a client cannot pin an old clause.
 *
 * <p><strong>The scan and the attachment checks run before the throttle.</strong> A document that
 * would be refused for carrying personal data, or a file of the wrong type, must not first spend
 * one of the person's hourly slots.
 */
@Component
public class SubmitFeedbackUseCase {

    static final int MAX_PER_HOUR = 10;

    private static final Logger LOG = LoggerFactory.getLogger(SubmitFeedbackUseCase.class);

    /** The only diagnostic keys a client may send; anything else is dropped, not refused. */
    static final Set<String> DIAGNOSTIC_KEYS = Set.of(
            "cli_version", "os", "arch", "at", "command", "error_code");

    private static final int MAX_DIAGNOSTIC_VALUE_LENGTH = 256;

    private final FeedbackService feedback;
    private final LegalTextVersions legal;
    private final NotificationService notifications;
    private final AuditLog audit;
    private final BlobStore blobs;
    private final TransactionTemplate transactions;

    public SubmitFeedbackUseCase(FeedbackService feedback, LegalTextVersions legal,
            NotificationService notifications, AuditLog audit, BlobStore blobs,
            PlatformTransactionManager transactionManager) {
        this.feedback = feedback;
        this.legal = legal;
        this.notifications = notifications;
        this.audit = audit;
        this.blobs = blobs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The text-only form, kept for the JSON route and for callers that have no attachments. */
    public Result submit(String content, String skillAddress, Map<String, Object> diagnostics,
            String consentVersion, boolean allowSensitive, AuthenticatedSubject subject) {
        return submit(content, skillAddress, diagnostics, consentVersion, allowSensitive,
                List.of(), subject);
    }

    public Result submit(String content, String skillAddress, Map<String, Object> diagnostics,
            String consentVersion, boolean allowSensitive, List<Upload> uploads,
            AuthenticatedSubject subject) {
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
        // **A key prefix is replaced, not confirmed** (v64 做 #13): a client that did not redact
        // first still gets its document stored, with the key replaced below. The three personal-data
        // categories are the ones the person has to confirm. Attachments are not scanned: the
        // preview says so, and ADR 0190 records why.
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
        List<Validated> validated = validateAttachments(uploads, at);
        assertThrottle(subject.userId(), at);

        String address = skillAddress == null ? null : FeedbackSafety.redactSecrets(skillAddress);
        List<String> uploaded = upload(validated);
        return persist(text, address, diagnostics, at, validated, uploaded, subject);
    }

    /**
     * The attachment rules, in the order the person experiences them: how many, what type, how
     * big, then the total. No object is uploaded and no row is written before every file passes.
     */
    private List<Validated> validateAttachments(List<Upload> uploads, String at) {
        List<Upload> list = uploads == null ? List.of() : uploads;
        if (list.size() > FeedbackAttachmentSafety.MAX_ATTACHMENTS) {
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOO_MANY,
                    "一条反馈最多带 " + FeedbackAttachmentSafety.MAX_ATTACHMENTS + " 个附件（实际 "
                            + list.size() + " 个）。",
                    List.of(new ApiError.Detail("attachment", "too_many")));
        }
        List<Validated> validated = new ArrayList<>(list.size());
        long total = 0;
        for (int position = 0; position < list.size(); position++) {
            Upload upload = list.get(position);
            String filename = FeedbackAttachmentSafety.filename(upload.filename());
            if (upload.file() == null) {
                throw unreadable(filename);
            }
            long size;
            Optional<String> mediaType;
            String sha;
            try {
                size = Files.size(upload.file());
                mediaType = FeedbackAttachmentSafety.detect(upload.file());
                sha = FeedbackAttachmentSafety.sha256(upload.file());
            } catch (IOException e) {
                throw unreadable(filename);
            }
            if (size <= 0) {
                throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_EMPTY,
                        "附件「" + filename + "」是空文件。",
                        List.of(new ApiError.Detail(filename, "empty")));
            }
            if (mediaType.isEmpty()) {
                throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TYPE,
                        "附件「" + filename + "」的类型不在允许范围内；允许："
                                + String.join("、", FeedbackAttachmentSafety.ALLOWED_TYPES) + "。",
                        List.of(new ApiError.Detail(filename, "type_not_allowed")));
            }
            String type = mediaType.get();
            long limit = FeedbackAttachmentSafety.limitBytes(type);
            if (size > limit) {
                throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOO_LARGE,
                        "附件「" + filename + "」超过单文件上限 " + limit + " 字节（实际 " + size
                                + " 字节）。",
                        List.of(new ApiError.Detail(filename, "too_large", size, limit)));
            }
            total += size;
            String id = Ulid.generate();
            validated.add(new Validated(new FeedbackAttachmentDraft(
                    id,
                    position,
                    filename,
                    type,
                    size,
                    sha,
                    FeedbackAttachmentSafety.objectKey(at, id, type)), upload.file()));
        }
        if (total > FeedbackAttachmentSafety.MAX_TOTAL_BYTES) {
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOTAL_TOO_LARGE,
                    "附件合计超过 " + FeedbackAttachmentSafety.MAX_TOTAL_BYTES + " 字节（实际 "
                            + total + " 字节）。",
                    List.of(new ApiError.Detail("attachments", "total_too_large",
                            total, FeedbackAttachmentSafety.MAX_TOTAL_BYTES)));
        }
        return validated;
    }

    private void assertThrottle(String userId, String at) {
        String since = Timestamps.format(Instant.parse(at).minus(1, ChronoUnit.HOURS));
        if (feedback.countSince(userId, since) >= MAX_PER_HOUR) {
            String retryAt = feedback.oldestSince(userId, since)
                    .map(oldest -> Timestamps.format(
                            Instant.parse(oldest).plus(1, ChronoUnit.HOURS)))
                    .orElse(Timestamps.format(Instant.parse(at).plus(1, ChronoUnit.HOURS)));
            throw new RequestException(ErrorCode.FEEDBACK_THROTTLED,
                    "1 小时内最多提交 " + MAX_PER_HOUR + " 条反馈；请在 " + retryAt + " 之后重试。");
        }
    }

    /**
     * Uploads every already-validated file and returns the keys that made it.
     *
     * <p>A failure after some keys were written deletes those keys before answering: the caller
     * must not be left with objects no row will ever name. A key that cannot be deleted is logged
     * with the key, which is the only handle an operator has for removing it by hand.
     */
    private List<String> upload(List<Validated> validated) {
        List<String> uploaded = new ArrayList<>(validated.size());
        try {
            for (Validated item : validated) {
                String key = item.draft().objectKey();
                // **Recorded before the write, not after it.** A PUT that stores the object and
                // then throws on its response leaves the key known only to this list; a failed key
                // that was never written is a no-op for an idempotent delete.
                uploaded.add(key);
                blobs.putObject(key, item.file(), item.draft().sizeBytes());
            }
            return uploaded;
        } catch (RuntimeException e) {
            LOG.warn("feedback attachment upload failed; removing {} recorded object key(s)",
                    uploaded.size(), e);
            deleteObjects(uploaded);
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_UNAVAILABLE,
                    "附件存储暂时不可用，本次反馈没有提交；请稍后重试。");
        }
    }

    /**
     * The three writes that must succeed or fail together.
     *
     * <p><strong>The upload is not in this transaction.</strong> The transaction is opened after
     * every object is in the bucket; if it throws, the objects are removed here because no row will
     * ever name them.
     */
    private Result persist(String text, String address, Map<String, Object> diagnostics, String at,
            List<Validated> validated, List<String> uploaded, AuthenticatedSubject subject) {
        List<FeedbackAttachmentDraft> drafts = validated.stream()
                .map(Validated::draft).toList();
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                Feedback stored = feedback.create(subject.userId(), address, text,
                        legal.privacy(), whitelist(diagnostics), at, drafts);
                String body = drafts.isEmpty()
                        ? "你提交的反馈已收到。要撤回并删除，运行 skmr feedback delete "
                                + stored.receipt() + " --yes。"
                        : "你提交的反馈与 " + drafts.size()
                                + " 个附件已收到。要撤回并删除，运行 skmr feedback delete "
                                + stored.receipt() + " --yes。";
                notifications.emit(new NotificationDraft(
                        NotificationKinds.ANNOUNCEMENT, null, null, null, null, null,
                        "反馈已收到：" + stored.receipt(), body, null,
                        List.of(subject.userId())), at);
                audit.record(new AuditEvent(subject.userId(), "feedback_submit", "feedback",
                        stored.id(), Map.of(
                                "receipt", stored.receipt(),
                                "skillAddress", address == null ? "" : address,
                                "attachments", String.valueOf(drafts.size()))));
                return new Result(stored.receipt(), stored.createdAt(), drafts);
            }));
        } catch (RuntimeException e) {
            deleteObjects(uploaded);
            throw e;
        }
    }

    private void deleteObjects(List<String> keys) {
        for (String key : keys) {
            try {
                blobs.deleteObject(key);
            } catch (RuntimeException e) {
                // The row that would have named this key was never written, so nothing retries it.
                // The key is the only handle an operator has.
                LOG.warn("could not remove uploaded feedback attachment {}; remove it by hand", key, e);
            }
        }
    }

    private static RequestException unreadable(String filename) {
        return new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_UNREADABLE,
                "附件「" + filename + "」读不出来；请确认文件还在、可读。",
                List.of(new ApiError.Detail(filename, "unreadable")));
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

    /** One spooled file the controller handed over; the use case owns nothing about its lifetime. */
    public record Upload(Path file, String filename) {
    }

    private record Validated(FeedbackAttachmentDraft draft, Path file) {
    }

    /**
     * @param receipt     what the CLI prints and the delete command takes
     * @param attachments the metadata of what was stored; empty for a text-only submission
     */
    public record Result(String receipt, String createdAt,
            List<FeedbackAttachmentDraft> attachments) {
    }
}

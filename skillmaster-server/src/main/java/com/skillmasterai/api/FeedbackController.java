package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobSettings;
import com.skillmasterai.modules.feedback.FeedbackAttachmentDraft;
import com.skillmasterai.modules.feedback.FeedbackAttachmentSafety;
import com.skillmasterai.modules.feedback.FeedbackConsent;
import com.skillmasterai.usecase.DeleteFeedbackUseCase;
import com.skillmasterai.usecase.ReadFeedbackConsentUseCase;
import com.skillmasterai.usecase.SubmitFeedbackUseCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The feedback plane (v64; attachments in v65): the consent notice, one submission, one withdrawal.
 *
 * <p>Thin, like the other controllers. The consent check, the scan, the throttle, the upload and
 * the notification all happen in the use cases; what is left here is the wire shape and the
 * spooling of multipart parts.
 *
 * <p><strong>There is no list route.</strong> The receipt is the only handle a person needs, and it
 * arrives in the notification. An operator reads the table directly, which is what keeps this
 * plane from growing an authorisation model it does not have. Attachments are not an exception:
 * there is no route that returns their bytes either (ADR 0190).
 */
@RestController
@RequestMapping(path = "/api/v1/feedback")
class FeedbackController {

    private final ReadFeedbackConsentUseCase consent;
    private final SubmitFeedbackUseCase submit;
    private final DeleteFeedbackUseCase delete;
    private final ObjectMapper json;
    private final BlobSettings blobs;

    FeedbackController(ReadFeedbackConsentUseCase consent, SubmitFeedbackUseCase submit,
            DeleteFeedbackUseCase delete, ObjectMapper json, BlobSettings blobs) {
        this.consent = consent;
        this.submit = submit;
        this.delete = delete;
        this.json = json;
        this.blobs = blobs;
    }

    @GetMapping(path = "/consent", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConsentResponse> consent() {
        FeedbackConsent notice = consent.consent();
        return ResponseEntity.ok(new ConsentResponse(notice.version(), notice.notice()));
    }

    /** v64's shape, unchanged for clients that have no attachments. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReceiptResponse> submit(@RequestBody FeedbackRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SubmitFeedbackUseCase.Result result = submit.submit(body.content(), body.skillAddress(),
                body.diagnostics(), body.consentVersion(),
                Boolean.TRUE.equals(body.allowSensitive()), subject);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResponse.of(result));
    }

    /**
     * One submission carrying images or short videos (v65).
     *
     * <p><strong>Each part is copied to a host-private temp file before the use case sees it.</strong>
     * The upload path needs a file it can hash and stream to OSS, and the servlet's own multipart
     * file may be memory-backed or moved after this method returns. The files are deleted in the
     * {@code finally}: a submission that fails must not leave the bytes sitting in the temp
     * directory. The use case reads them once, uploads them, and writes rows; after that the bytes
     * are in the bucket and the row names the key.
     *
     * <p><strong>No attachment is accepted through the JSON route.</strong> A JSON body cannot carry
     * binary parts, and accepting a base64 field would make every attachment travel twice in memory.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReceiptResponse> submitMultipart(
            @RequestParam("content") String content,
            @RequestParam(value = "skill_address", required = false) String skillAddress,
            @RequestParam(value = "diagnostics", required = false) String diagnostics,
            @RequestParam("consent_version") String consentVersion,
            @RequestParam(value = "allow_sensitive", required = false) Boolean allowSensitive,
            @RequestPart(value = "attachment", required = false) List<MultipartFile> attachments,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        List<Path> spooled = new ArrayList<>();
        try {
            checkBeforeSpooling(attachments);
            List<SubmitFeedbackUseCase.Upload> uploads = new ArrayList<>();
            if (attachments != null) {
                for (MultipartFile part : attachments) {
                    Path file = spool(part);
                    spooled.add(file);
                    uploads.add(new SubmitFeedbackUseCase.Upload(file, part.getOriginalFilename()));
                }
            }
            SubmitFeedbackUseCase.Result result = submit.submit(content, skillAddress,
                    diagnostics(diagnostics), consentVersion,
                    Boolean.TRUE.equals(allowSensitive), uploads, subject);
            return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResponse.of(result));
        } finally {
            for (Path file : spooled) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // The request is over; a leftover temp file holds feedback bytes. It is under
                    // the host's private temp directory, and the next startup's OS sweep removes it.
                }
            }
        }
    }

    @DeleteMapping(path = "/{receipt}")
    ResponseEntity<Void> delete(@PathVariable String receipt,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        delete.delete(receipt, subject);
        return ResponseEntity.noContent().build();
    }

    /**
     * The diagnostics part is a JSON string because it is a map; the rest are plain fields. A
     * malformed value is the caller's error, not a server bug, so it is a readable 400.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnostics(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, Map.class);
        } catch (RuntimeException e) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "diagnostics 不是有效的 JSON 对象。");
        }
    }

    /**
     * Refuses the obvious violations before a second copy of every part is written.
     *
     * <p>The servlet has already buffered the parts under its own multipart ceiling (520 MB, sized
     * for skill publish), so this is not the first copy; it is the one {@link #spool} would add in
     * the host's temp directory. A submission refused for count, a part over the video ceiling or
     * the running total therefore costs no spooling at all. The empty-file, type and type-specific
     * size checks still run in the use case, because the type is decided from the bytes, not from
     * the name.
     */
    private static void checkBeforeSpooling(List<MultipartFile> attachments) {
        if (attachments == null) {
            return;
        }
        if (attachments.size() > FeedbackAttachmentSafety.MAX_ATTACHMENTS) {
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOO_MANY,
                    "一条反馈最多带 " + FeedbackAttachmentSafety.MAX_ATTACHMENTS + " 个附件（实际 "
                            + attachments.size() + " 个）。",
                    List.of(new ApiError.Detail("attachment", "too_many")));
        }
        long total = 0;
        for (MultipartFile part : attachments) {
            String filename = FeedbackAttachmentSafety.filename(part.getOriginalFilename());
            long size = part.getSize();
            if (size > FeedbackAttachmentSafety.MAX_VIDEO_BYTES) {
                throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOO_LARGE,
                        "附件「" + filename + "」超过单文件上限 "
                                + FeedbackAttachmentSafety.MAX_VIDEO_BYTES + " 字节（实际 " + size
                                + " 字节）。",
                        List.of(new ApiError.Detail(filename, "too_large", size,
                                FeedbackAttachmentSafety.MAX_VIDEO_BYTES)));
            }
            total += size;
            if (total > FeedbackAttachmentSafety.MAX_TOTAL_BYTES) {
                throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_TOTAL_TOO_LARGE,
                        "附件合计超过 " + FeedbackAttachmentSafety.MAX_TOTAL_BYTES + " 字节（实际 "
                                + total + " 字节）。",
                        List.of(new ApiError.Detail("attachments", "total_too_large",
                                total, FeedbackAttachmentSafety.MAX_TOTAL_BYTES)));
            }
        }
    }

    private Path spool(MultipartFile part) {
        Path file;
        try {
            // **The same directory the blob seam spools into.** A deployment that moved that
            // directory onto a large volume expects every upload to land there; the JVM's default
            // temp directory may be a small one (v39 sized this setting for a large import).
            file = Files.createTempFile(blobs.tempDirPath(), "feedback-upload-", ".bin");
        } catch (IOException | RuntimeException e) {
            // A server-side staging failure is not the caller's file: it would fail the same way on
            // every retry, so it is answered as unavailable, not as "unreadable attachment".
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_UNAVAILABLE,
                    "服务端无法暂存附件；请稍后重试。");
        }
        try {
            part.transferTo(file);
            try {
                Files.setPosixFilePermissions(file,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // A non-POSIX file system has no mode bits; the temp directory is still private.
            }
            return file;
        } catch (IOException | IllegalStateException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Nothing better to do; the original failure is what the caller needs.
            }
            throw new RequestException(ErrorCode.FEEDBACK_ATTACHMENT_UNREADABLE,
                    "附件上传中断，读不出来；请重新提交。");
        }
    }

    /** The wire body; Jackson's global snake_case strategy turns these into the field names. */
    record FeedbackRequest(String content, String skillAddress, Map<String, Object> diagnostics,
            String consentVersion, Boolean allowSensitive) {
    }

    record ConsentResponse(String version, String notice) {
    }

    record ReceiptResponse(String receipt, String createdAt, List<AttachmentResponse> attachments) {

        static ReceiptResponse of(SubmitFeedbackUseCase.Result result) {
            return new ReceiptResponse(
                    result.receipt(),
                    result.createdAt(),
                    result.attachments().stream().map(AttachmentResponse::of).toList());
        }
    }

    record AttachmentResponse(String filename, String mediaType, long sizeBytes, String sha256) {

        static AttachmentResponse of(FeedbackAttachmentDraft draft) {
            return new AttachmentResponse(draft.filename(), draft.mediaType(),
                    draft.sizeBytes(), draft.sha256());
        }
    }
}

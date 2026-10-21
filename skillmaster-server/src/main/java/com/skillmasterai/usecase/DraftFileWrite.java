package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.PreconditionRequiredException;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.ingest.SkillPaths;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.DraftWriteResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * The flow every draft write shares: resolve the address, gate the row, lock it, compare the
 * baseline, and leave one audit row.
 *
 * <p><strong>Four use cases, one flow.</strong> Writing, deleting, moving and copying differ only in
 * the statement that changes {@code version_file}; permission, state, {@code If-Match}, limits and
 * the audit trail are the same question asked four times. Writing them out four times is how the
 * four come to answer "may this caller write here" differently — the failure this base class exists
 * to make impossible.
 *
 * <p>The transaction annotation stays on the subclasses' public methods: §2.5 rule 2 puts a
 * cross-module boundary at the use-case layer, and a base class's protected helper is inside one.
 */
abstract class DraftFileWrite {

    protected static final String BODY_RELPATH = "SKILL.md";

    protected final NamespaceService namespaces;
    protected final SkillVersionService versions;
    protected final SkillMetadataService metadata;
    protected final BlobStore blobs;
    protected final AuditLog audit;
    protected final ObjectMapper objectMapper;

    protected DraftFileWrite(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.blobs = blobs;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** ⓪: no such skill, or not visible to this credential, is one 404 — before any rung check. */
    protected Optional<Access> accessOf(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
    }

    /**
     * A write's body. {@code text} is required and may be empty — an absent field is not an empty
     * file, it is a request that forgot its content, and treating the two alike silently truncates
     * whatever the path already held (v8 TD §接口: `write` carries `path, text`; 空字符串是合法内容).
     */
    protected static String requireText(String text) {
        if (text == null) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a write must carry its text",
                    List.of(new ApiError.Detail("text", "required")));
        }
        return text;
    }

    /**
     * A path a change is writing <em>to</em>: the ordinary rules, plus the platform's reserved
     * paths (ADR 0069).
     *
     * <p>Separate from {@link #requirePath} because a delete or the source side of a rename must
     * stay permitted: legacy versions can hold a file at the reserved path, and refusing to let it
     * go would make that data impossible to clean up.
     */
    protected static String requireTargetPath(String relpath) {
        requirePath(relpath);
        com.skillmasterai.modules.ingest.ReservedPaths.requireNotReserved(relpath);
        return relpath;
    }

    /** A path as the write path accepts it — the same rules an upload's entries obey. */
    protected static String requirePath(String relpath) {
        Optional<SkillPaths.Issue> issue = SkillPaths.check(relpath);
        if (issue.isPresent()) {
            SkillPaths.Issue problem = issue.get();
            throw new com.skillmasterai.common.RequestException(ErrorCode.INVALID_PATH,
                    problem.message(),
                    List.of(new com.skillmasterai.common.ApiError.Detail(problem.field(),
                            problem.issue())));
        }
        return relpath;
    }

    /**
     * The baseline a write says it is based on, normalised to bare lowercase hex.
     *
     * <p>Three spellings are accepted because three clients exist: the server's own
     * {@code "sha256:<hex>"} (a quoted ETag), the unquoted form, and the bare hex a person might
     * paste. Anything else is a request that cannot be compared, and 428 is the answer that tells
     * the caller what was missing rather than what was wrong.
     */
    protected static String requireIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException(
                    "a draft write must carry If-Match with the version digest it is based on");
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("sha256:")) {
            value = value.substring("sha256:".length());
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new PreconditionRequiredException(
                    "If-Match must be the version digest: \"sha256:<hex>\"");
        }
        return value;
    }

    /** The baseline still describes the row, or nothing is written. */
    protected static void requireBaseline(String baseline, VersionSummary locked) {
        if (!baseline.equals(locked.digest())) {
            throw new ConflictException(ErrorCode.DIGEST_MISMATCH,
                    "this version changed since it was read; re-read it and try again",
                    List.of(new com.skillmasterai.common.ApiError.Detail("current_digest",
                            locked.digest())));
        }
    }

    /** The root {@code SKILL.md}, parsed and checked against the address. */
    protected SkillMetadata documentOf(byte[] bytes, String addressName) {
        SkillDocument.Document document = SkillDocument.parse(bytes);
        SkillDocument.requireNameMatches(addressName, document);
        return new SkillMetadata(document.name(), document.title(), document.description(),
                objectMapper.writeValueAsString(document.frontmatter()));
    }

    /**
     * 512 files and 16 MiB, the same ceilings a submission obeys.
     *
     * <p>Checked after the change and before it is committed: a write that would cross a ceiling is
     * refused with the same {@code INVALID_UPLOAD} details the upload route uses, so a client reads
     * one vocabulary for one rule.
     */
    protected static void requireWithinLimits(Manifest next) {
        if (next.fileCount() > IngestLimits.STANDARD_MAX_FILES) {
            throw new IngestException("the skill would have more than "
                    + IngestLimits.STANDARD_MAX_FILES + " files", "files", "too_many_files");
        }
        if (next.totalBytes() > IngestLimits.STANDARD_MAX_BYTES) {
            throw new IngestException("the skill would exceed " + IngestLimits.STANDARD_MAX_BYTES
                    + " bytes", "files", "too_large");
        }
    }

    /**
     * The touched path may not be both a file and a directory.
     *
     * <p>Only the path this request changes is checked. v7 drafts may already hold {@code a} and
     * {@code a/b} — {@code SkillPaths} permits it — and an unrelated write must not be refused for
     * data this version does not clean up (v8 TD §接口).
     */
    protected static void requireTree(Manifest manifest, String target) {
        if (target == null) {
            return;
        }
        for (ManifestEntry entry : manifest.entries()) {
            String other = entry.relpath();
            if (!other.equals(target)
                    && (other.startsWith(target + "/") || target.startsWith(other + "/"))) {
                throw new com.skillmasterai.common.RequestException(
                        ErrorCode.INVALID_PATH,
                        "'" + target + "' and '" + other + "' cannot both be files",
                        List.of(new com.skillmasterai.common.ApiError.Detail(target,
                                "conflicts_with_existing_path")));
            }
        }
    }

    /** One audit row, in the same transaction as the change it describes. */
    protected void record(AuthenticatedSubject subject, Access access, VersionSummary before,
            VersionSummary after, String op, String path, String from, String to) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", before.id());
        detail.put("code", before.id());
        detail.put("op", op);
        if (path != null) {
            detail.put("path", path);
        }
        if (from != null) {
            detail.put("from", from);
        }
        if (to != null) {
            detail.put("to", to);
        }
        detail.put("digest_before", "sha256:" + before.digest());
        detail.put("digest_after", "sha256:" + after.digest());
        detail.put("files", after.fileCount());
        // The size the operation started from, which is the only number that says how much moved.
        detail.put("bytes", before.totalBytes());
        audit.record(new AuditEvent(subject.userId(), "version_file_write", "skill",
                access.skillId(), detail));
    }

    /** The answer both the controller and the audit use. */
    protected Optional<DraftWriteResult> result(Access access, VersionSummary after) {
        return Optional.of(new DraftWriteResult(access, after));
    }

    /** The no-op answer: the row is what it was, and nothing was written or audited. */
    protected Optional<DraftWriteResult> unchanged(Access access, VersionSummary locked) {
        return Optional.of(new DraftWriteResult(access, locked));
    }
}

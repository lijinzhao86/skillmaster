package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.PreconditionRequiredException;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.VersionFileNotFoundException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionState;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.CreateDraftCommand;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.usecase.model.DraftEditResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a draft from a base version plus the editor's first content change (v8 TD §架构).
 *
 * <p><strong>Why the row is born here and not when the page opens.</strong> Copying a live version
 * verbatim would carry its digest, and {@code UNIQUE (skill_id, digest)} refuses two rows with one
 * content. The page therefore holds a base in memory until the first content change; this use case
 * reads that base, applies the change in memory, and inserts the row and its files together.
 *
 * <p>The base is resolved by the rule the page also uses: the live version, else the newest draft
 * this caller may see, else the skill row's own minimal {@code SKILL.md}. Revoked and discarded
 * rows are visible to some rungs but are never a base.
 */
@Component
public class CreateDraftFromVersionUseCase extends DraftFileWrite {

    private static final String SOURCE = "web";

    public CreateDraftFromVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    /**
     * The base a first write would start from, as a reference — what the CLI reads before it sends
     * the write that carries it.
     *
     * <p><strong>Resolved by the same method the create path uses</strong>, so "which draft is the
     * newest" has one answer. Returning the content too would make every first write download a
     * whole version, which is the cost this route exists to avoid.
     *
     * @return empty when the caller cannot see the skill; a {@code ResolvedBase} with null fields
     *         when the base would be blank (no live version and no readable draft)
     * @throws NotPermittedException when they can see it and hold less than {@code write}
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedBase> resolveBaseFor(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("reading the base of '" + access.name()
                    + "' needs the `write` rung");
        }
        Optional<Base> base = resolveBase(access, null);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        SkillSnapshot snapshot = base.get().snapshot;
        if (snapshot == null) {
            return Optional.of(new ResolvedBase(null, null));
        }
        // A published row is addressed by its name, or by its digest when its author declared no
        // name (ADR 0033); a draft is addressed by its row id (ADR 0063). Both are the spellings
        // the write path accepts back.
        // The digest the write path compares is the manifest's, not the row's (they are equal after
        // `settled`, but returning the same source removes the chance of drifting apart).
        String digest = "sha256:" + snapshot.manifest().digest();
        String pin = VersionState.DRAFT.equals(snapshot.state())
                ? snapshot.id()
                : snapshot.version() != null ? snapshot.version() : digest;
        return Optional.of(new ResolvedBase(pin, digest));
    }

    /** A base as the wire spells it: what to send back as {@code from} and {@code from_digest}. */
    public record ResolvedBase(String pin, String digest) {
    }

    @Transactional
    public Optional<DraftEditResult> create(String namespaceSlug, String name,
            CreateDraftCommand command, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("creating a version of '" + access.name()
                    + "' needs the `write` rung");
        }

        String draftName = VersionLabels.name(command.name());
        String note = VersionLabels.note(command.note());
        DraftEdit edit = command.edit();
        if (edit == null) {
            throw new RequestException(ErrorCode.INVALID_REQUEST, "the first content change is required",
                    List.of(new ApiError.Detail("edit", "required")));
        }

        Optional<Base> resolvedBase = resolveBase(access, command.from());
        if (resolvedBase.isEmpty()) {
            return Optional.empty();
        }
        Base base = resolvedBase.get();
        requireBaseDigest(command.fromDigest(), base);

        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        SkillMetadata baseDeclaration;
        if (base.snapshot != null) {
            for (ManifestEntry entry : base.snapshot.manifest().entries()) {
                entries.put(entry.relpath(), entry);
            }
            baseDeclaration = new SkillMetadata(access.name(), base.snapshot.title(),
                    base.snapshot.description(), base.snapshot.frontmatterJson());
        } else {
            byte[] template = blankTemplate(access);
            entries.put(BODY_RELPATH, store(BODY_RELPATH, template));
            // The template is a SKILL.md like any other and goes through the same parse, so a
            // description YAML cannot carry is refused here rather than discovered at publish. Its
            // parsed metadata is not used: the row's metadata is the skill row's — the empty base
            // `?base=none` returns (v8 TD §数据模型: title/description/frontmatter 取底稿那一版).
            documentOf(template, access.name());
            baseDeclaration = new SkillMetadata(access.name(), access.title(), access.description(),
                    access.frontmatterJson());
        }

        SkillMetadata changed = apply(access, edit, entries);
        SkillMetadata declaration = changed == null ? baseDeclaration : changed;
        Manifest next = Manifest.of(new ArrayList<>(entries.values()));
        requireWithinLimits(next);
        requireTree(next, edit.targetPath());

        String at = Timestamps.now();
        VersionSummary created = versions.underExclusiveWrite(
                () -> versions.submitFromBase(access, declaration, next, draftName, note,
                        subject.userId(), SOURCE, at));
        recordCreate(subject, access, created, base, edit);
        return Optional.of(new DraftEditResult(access, created, next));
    }

    /**
     * The base the page also resolves. An explicit {@code from} is the page saying which version it
     * read; the default is the rule in v8 TD §读.
     */
    private Optional<Base> resolveBase(Access access, VersionPin from) {
        if (from != null) {
            Optional<SkillSnapshot> snapshot = settled(access, from);
            if (snapshot.isEmpty()) {
                return Optional.empty();
            }
            if (!VersionState.PUBLISHED.equals(snapshot.get().state())
                    && !VersionState.DRAFT.equals(snapshot.get().state())) {
                throw new RequestException(ErrorCode.INVALID_REQUEST,
                        "a base must be a published version or a draft",
                        List.of(new ApiError.Detail("from", "not_a_base")));
            }
            return Optional.of(new Base(snapshot.get()));
        }
        if (access.currentVersionId() != null) {
            Optional<SkillSnapshot> live = settled(access, new VersionPin.Latest());
            if (live.isPresent()) {
                return Optional.of(new Base(live.get()));
            }
            // The pointer moved between reading the skill and reading the version. It is no base
            // any more; the draft ladders below still are.
        }
        for (VersionSummary version : versions.authorVersions(access)) {
            if (!VersionState.DRAFT.equals(version.state())) {
                continue;
            }
            Optional<SkillSnapshot> snapshot = settled(access, new VersionPin.RowId(version.id()));
            // Gone or no longer a draft by the time it was read (somebody discarded it): the list
            // is a moment behind the rows, and the next candidate is still a base.
            if (snapshot.isPresent() && VersionState.DRAFT.equals(snapshot.get().state())) {
                return Optional.of(new Base(snapshot.get()));
            }
        }
        return Optional.of(new Base(null));
    }

    /**
     * A snapshot whose row and manifest are one state.
     *
     * <p>{@code authorSnapshot} reads the row and then its files, and this transaction is READ
     * COMMITTED: a write committed between the two would hand back one version's metadata with
     * another's manifest. Such a pair is re-read a couple of times; if the writer keeps moving it,
     * the last pair still answers the request, and {@code requireBaseDigest} compares the digest of
     * the bytes that would actually be copied, so a caller holding the older digest is told to
     * re-read rather than given a mixture.
     */
    private Optional<SkillSnapshot> settled(Access access, VersionPin pin) {
        Optional<SkillSnapshot> snapshot = versions.authorSnapshot(access, pin);
        for (int attempt = 0; attempt < 2 && snapshot.isPresent()
                && !snapshot.get().digest().equals(snapshot.get().manifest().digest()); attempt++) {
            snapshot = versions.authorSnapshot(access, pin);
        }
        return snapshot;
    }

    /**
     * The base the page saw and the base this request read are the same bytes, or nothing happens.
     * A blank base has no digest, and one supplied anyway is a request that describes something
     * else.
     */
    private static void requireBaseDigest(String given, Base base) {
        if (base.snapshot == null) {
            if (given != null && !given.isBlank()) {
                throw new RequestException(ErrorCode.INVALID_REQUEST,
                        "an empty base has no digest to compare",
                        List.of(new ApiError.Detail("from_digest", "unexpected")));
            }
            return;
        }
        if (given == null || given.isBlank()) {
            throw new PreconditionRequiredException(
                    "creating from a version must carry the digest it was read at");
        }
        String baseline = normaliseDigest(given);
        // The manifest's digest, not the row's: the manifest is the bytes this request would copy,
        // and the two are read separately (see `settled`).
        String current = base.snapshot.manifest().digest();
        if (!baseline.equals(current)) {
            throw new ConflictException(ErrorCode.DIGEST_MISMATCH,
                    "the base changed since it was read; re-read it and try again",
                    List.of(new ApiError.Detail("current_digest", current)));
        }
    }

    private static String normaliseDigest(String value) {
        String digest = value.trim();
        if (digest.length() >= 2 && digest.startsWith("\"") && digest.endsWith("\"")) {
            digest = digest.substring(1, digest.length() - 1);
        }
        if (digest.startsWith("sha256:")) {
            digest = digest.substring("sha256:".length());
        }
        if (!digest.matches("[0-9a-f]{64}")) {
            // Missing is 428 (`if_match_required`); malformed is a request that cannot be compared,
            // and 400 names the field instead of telling the caller to re-read the version.
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "from_digest must be the version digest: \"sha256:<hex>\"",
                    List.of(new ApiError.Detail("from_digest", "invalid_format")));
        }
        return digest;
    }

    /** Applies one edit in memory; returns new metadata when the edit touched the root SKILL.md. */
    private SkillMetadata apply(Access access, DraftEdit edit, Map<String, ManifestEntry> entries) {
        return switch (edit.op() == null ? DraftEdit.WRITE : edit.op()) {
            case DraftEdit.WRITE -> {
                String path = requireTargetPath(edit.path());
                byte[] bytes = requireText(edit.text()).getBytes(StandardCharsets.UTF_8);
                entries.put(path, store(path, bytes));
                yield BODY_RELPATH.equals(path) ? documentOf(bytes, access.name()) : null;
            }
            case DraftEdit.DELETE -> {
                String path = requirePath(edit.path());
                if (BODY_RELPATH.equals(path)) {
                    throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                            "SKILL.md is the skill's body and cannot be removed", "path", path);
                }
                if (!entries.containsKey(path)) {
                    throw new VersionFileNotFoundException(path);
                }
                if (entries.size() <= 1) {
                    throw new ConflictException(ErrorCode.LAST_FILE_CANNOT_BE_DELETED,
                            "a version cannot have no files at all", "path", path);
                }
                entries.remove(path);
                yield null;
            }
            case DraftEdit.RENAME -> {
                String from = requirePath(edit.from());
                String to = requireTargetPath(edit.to());
                ManifestEntry source = entries.get(from);
                if (source == null) {
                    throw new VersionFileNotFoundException(from);
                }
                if (entries.containsKey(to)) {
                    throw new ConflictException(ErrorCode.FILE_ALREADY_EXISTS,
                            "this version already has a file at '" + to + "'", "to", to);
                }
                if (BODY_RELPATH.equals(from)) {
                    throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                            "SKILL.md is the skill's body and cannot be moved away", "path", from);
                }
                entries.remove(from);
                entries.put(to, new ManifestEntry(to, source.blobSha256(), source.size(),
                        source.isBinary()));
                yield BODY_RELPATH.equals(to)
                        ? documentOf(blobs.get(source.blobSha256()), access.name())
                        : null;
            }
            default -> throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "the edit must be write, delete or rename",
                    List.of(new ApiError.Detail("op", "unknown_value")));
        };
    }

    private ManifestEntry store(String relpath, byte[] bytes) {
        BlobStore.BlobRef blob = blobs.put(bytes);
        return new ManifestEntry(relpath, blob.sha256Hex(), blob.size(),
                new IngestedFile(relpath, bytes).isBinary());
    }

    /**
     * The smallest valid body: the template v8 TD §数据模型 defines for an empty base.
     *
     * <p>The bytes themselves come from {@link SkillMdTemplate}, shared with v9's create-skill so
     * the quoting rules cannot drift between the two ways a draft is born.
     */
    private static byte[] blankTemplate(Access access) {
        String title = access.title() == null || access.title().isBlank()
                ? access.name() : access.title();
        return SkillMdTemplate.render(access.name(), access.description(), title);
    }

    private void recordCreate(AuthenticatedSubject subject, Access access, VersionSummary created,
            Base base, DraftEdit edit) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", created.id());
        detail.put("code", created.id());
        detail.put("op", "create");
        if (edit.path() != null) {
            detail.put("path", edit.path());
        }
        if (edit.from() != null) {
            detail.put("from", edit.from());
        }
        if (edit.to() != null) {
            detail.put("to", edit.to());
        }
        detail.put("digest_after", "sha256:" + created.digest());
        detail.put("files", created.fileCount());
        if (base.snapshot != null) {
            detail.put("base_pin", base.snapshot.addressSuffix());
            detail.put("base_digest", "sha256:" + base.snapshot.manifest().digest());
        }
        audit.record(new AuditEvent(subject.userId(), "version_file_write", "skill",
                access.skillId(), detail));
    }

    private record Base(SkillSnapshot snapshot) {
    }
}

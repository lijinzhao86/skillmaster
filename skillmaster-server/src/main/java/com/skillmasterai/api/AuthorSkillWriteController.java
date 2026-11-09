package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillPublishedException;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.CopySkillUseCase;
import com.skillmasterai.usecase.CreateDraftFromVersionUseCase;
import com.skillmasterai.usecase.CreateSkillUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.CopyDraftFileUseCase;
import com.skillmasterai.usecase.DeleteDraftFileUseCase;
import com.skillmasterai.usecase.DeleteNeverPublishedSkillUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.MoveDraftFileUseCase;
import com.skillmasterai.usecase.ReadAuthorVersionUseCase;
import com.skillmasterai.usecase.UpdateDraftNameUseCase;
import com.skillmasterai.usecase.WriteDraftFileUseCase;
import com.skillmasterai.usecase.UpdateDraftNoteUseCase;
import com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase;
import com.skillmasterai.usecase.model.CreateDraftCommand;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.usecase.model.DraftEditResult;
import com.skillmasterai.usecase.model.DraftWriteResult;
import com.skillmasterai.usecase.model.SubmittedSkill;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The author plane on {@code /api/v1} (ADR 0061).
 *
 * <p>Bearer, like every other API route: the scope gate is the existing {@code /api/v1/**} one
 * (reads need {@code skills:read}, writes {@code skills:write}), and the rung is decided inside the
 * use cases. What this plane deliberately does not offer is any action that changes what a caller
 * gets: publish, rollback, revoke, restore and the skill switch have no route here at all.
 *
 * <p>Reads do not reuse the browser page's resource. A CLI wants one version at a time, with a
 * cursor; the page wants everything at once. Two shapes, two readers.
 *
 * <p><strong>One write here does take something away</strong> since v37: deleting a skill that has
 * never been published (ADR 0127). It is the first exception to the paragraph above, and it is
 * narrow on purpose — a skill with any version that went live stays deletable only on the web.
 */
@RestController
@RequestMapping(path = AuthorSkillRoutes.BASE)
class AuthorSkillWriteController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ReadAuthorVersionUseCase readVersion;
    private final UpdateDraftNameUseCase updateName;
    private final UpdateDraftNoteUseCase updateNote;
    private final UpdateDraftReleaseTypeUseCase updateReleaseType;
    private final DiscardSkillVersionUseCase discardVersion;
    private final WriteDraftFileUseCase writeFile;
    private final DeleteDraftFileUseCase deleteFile;
    private final MoveDraftFileUseCase moveFile;
    private final CopyDraftFileUseCase copyFile;
    private final CreateSkillUseCase createSkill;
    private final CopySkillUseCase copySkill;
    private final CreateDraftFromVersionUseCase createDraft;
    private final DraftRecorder drafts;
    private final AccountDirectory accounts;
    private final DeleteNeverPublishedSkillUseCase deleteNeverPublished;
    private final com.skillmasterai.modules.blob.BlobSettings blobs;

    AuthorSkillWriteController(ReadAuthorVersionUseCase readVersion,
            UpdateDraftNameUseCase updateName,
            UpdateDraftNoteUseCase updateNote,
            UpdateDraftReleaseTypeUseCase updateReleaseType,
            DiscardSkillVersionUseCase discardVersion,
            WriteDraftFileUseCase writeFile,
            DeleteDraftFileUseCase deleteFile,
            MoveDraftFileUseCase moveFile,
            CopyDraftFileUseCase copyFile,
            CreateSkillUseCase createSkill,
            CopySkillUseCase copySkill,
            CreateDraftFromVersionUseCase createDraft,
            DraftRecorder drafts,
            AccountDirectory accounts,
            DeleteNeverPublishedSkillUseCase deleteNeverPublished,
            com.skillmasterai.modules.blob.BlobSettings blobs) {
        this.readVersion = readVersion;
        this.updateName = updateName;
        this.updateNote = updateNote;
        this.discardVersion = discardVersion;
        this.writeFile = writeFile;
        this.deleteFile = deleteFile;
        this.moveFile = moveFile;
        this.copyFile = copyFile;
        this.createSkill = createSkill;
        this.copySkill = copySkill;
        this.createDraft = createDraft;
        this.updateReleaseType = updateReleaseType;
        this.drafts = drafts;
        this.accounts = accounts;
        this.deleteNeverPublished = deleteNeverPublished;
        this.blobs = blobs;
    }

    /**
     * Soft-deletes a skill that has never had a version go live (v37, ADR 0127).
     *
     * <p>The four answers are the product's: nothing (404, the same as "not yours" when the skill is
     * unreadable), visible but not the owner (403), owner with a published version (409, pointing at
     * the web), and deleted (204). The decision about "published" is made inside the delete's own
     * statement, not here.
     */
    @PostMapping(AuthorSkillRoutes.DELETE_NEVER_PUBLISHED)
    ResponseEntity<Void> deleteNeverPublished(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return switch (deleteNeverPublished.delete(namespace, name, subject)) {
            case DELETED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> throw new SkillNotFoundException();
            case NOT_OWNER -> throw new NotPermittedException(
                    "deleting '" + name + "' is its owner's to do");
            case PUBLISHED -> throw new SkillPublishedException(name);
        };
    }

    @PatchMapping(AuthorSkillRoutes.NAME)
    ResponseEntity<AuthorVersionResponse.Written> rename(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestBody NameBody body, @AuthenticationPrincipal AuthenticatedSubject subject) {
        VersionPin pin = pinOf(version);
        updateName.rename(namespace, name, pin, body.name(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(written(namespace, name, pin, subject));
    }

    @PatchMapping(AuthorSkillRoutes.NOTE)
    ResponseEntity<AuthorVersionResponse.Written> note(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestBody NoteBody body, @AuthenticationPrincipal AuthenticatedSubject subject) {
        VersionPin pin = pinOf(version);
        updateNote.setNote(namespace, name, pin, body.note(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(written(namespace, name, pin, subject));
    }

    /** Sets or clears a draft's release type (ADR 0091). Rung: {@code write}. */
    @PatchMapping(AuthorSkillRoutes.RELEASE_TYPE)
    ResponseEntity<AuthorVersionResponse.Written> releaseType(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestBody ReleaseTypeBody body, @AuthenticationPrincipal AuthenticatedSubject subject) {
        VersionPin pin = pinOf(version);
        updateReleaseType.setReleaseType(namespace, name, pin, body.release_type(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(written(namespace, name, pin, subject));
    }

    @PostMapping(AuthorSkillRoutes.DISCARD)
    ResponseEntity<AuthorVersionResponse.Written> discard(@PathVariable String namespace,
            @PathVariable String name, @RequestBody VersionChoice choice,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        requirePin(choice.version());
        VersionPin pin = pinOf(choice.version());
        discardVersion.discard(namespace, name, pin, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(written(namespace, name, pin, subject));
    }

    /**
     * The actions that change what callers see have no route here (ADR 0031, ADR 0046, ADR 0061).
     *
     * <p>These four literals are mapped to 404 rather than left to fall through: without them
     * {@code /versions/publish} would match the {@code /versions/{version}} path pattern and answer
     * 405, which reads like "the route exists but you used the wrong method" — the opposite of what
     * this plane promises. A 404 says the action is not here, which is the truth and points at the
     * browser.
     */
    @PostMapping({AuthorSkillRoutes.VERSIONS + "/publish", AuthorSkillRoutes.VERSIONS + "/rollback",
            AuthorSkillRoutes.VERSIONS + "/revoke", AuthorSkillRoutes.VERSIONS + "/restore",
            AuthorSkillRoutes.VERSIONS + "/delete"})
    ResponseEntity<Void> noSuchAction() {
        return ResponseEntity.notFound().build();
    }

    /**
     * Replaces or creates one file of a draft — {@code skill write} (v7 做 #6).
     *
     * <p>The body is the file's bytes, not a JSON object: a write must put exactly what it was
     * given into the blob, and any envelope would be a second place the content could be altered.
     * {@code If-Match} is required rather than optional — see {@code PreconditionRequiredException}.
     *
     * <p><strong>An empty file is a legal write, and its body is zero bytes rather than absent.</strong>
     * Spring's default treats a zero-length body as "no body at all" for a required {@code byte[]},
     * which turned {@code skill write <addr> empty.txt < /dev/null} into a 500. {@code required=false}
     * plus the null-to-empty mapping keeps the same route for both, since availing an absent body is
     * the only thing the flag changes here.
     */
    @PutMapping(AuthorSkillRoutes.FILES)
    ResponseEntity<AuthorVersionResponse.Written> writeFile(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version, @PathVariable String relpath,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request, @AuthenticationPrincipal AuthenticatedSubject subject) {
        String path = CapturedPath.relativeToSkillRoot(relpath);
        try (ReceivedFile received = ReceivedFile.read(request,
                IngestLimits.STANDARD_MAX_FILE_BYTES, blobs.tempDirPath(), path)) {
            // One route carries both `skill write` (text) and `skill put` (any bytes). The binary
            // probe is the same signal the manifest stores: a text write keeps the v33 audit shape
            // (op=write), a binary upload records add/overwrite and its own size (A10 / A12).
            DraftWriteResult written = (received.inMemory()
                    ? (received.binary()
                        ? writeFile.upload(namespace, name, rowIdOf(version), path, received.bytes(),
                                true, ifMatch, subject)
                        : writeFile.write(namespace, name, rowIdOf(version), path, received.bytes(),
                                ifMatch, subject))
                    : writeFile.writeFromFile(namespace, name, rowIdOf(version), path,
                            received.file(), received.size(), received.binary(),
                            received.binary(), ifMatch, subject))
                    .orElseThrow(SkillNotFoundException::new);
            return ResponseEntity.ok(writtenFrom(namespace, written));
        }
    }

    /** Removes one file of a draft — {@code skill rm} (v7 做 #8). */
    @DeleteMapping(AuthorSkillRoutes.FILES)
    ResponseEntity<AuthorVersionResponse.Written> deleteFile(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version, @PathVariable String relpath,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        DraftWriteResult result = deleteFile
                .delete(namespace, name, rowIdOf(version),
                        CapturedPath.relativeToSkillRoot(relpath), ifMatch, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(writtenFrom(namespace, result));
    }

    /**
     * {@code mv} / {@code cp} — {@code skill mv} / {@code skill cp} (v7 做 #9).
     *
     * <p>A body describing the operation rather than a {@code /files/{*relpath}/move} sub-route:
     * the wildcard captures everything after {@code /files/}, so a trailing {@code /move} would be
     * read as a file called {@code move} (v7 TD §仍开放 3).
     */
    @PostMapping(AuthorSkillRoutes.ONE_VERSION + "/files")
    ResponseEntity<AuthorVersionResponse.Written> moveOrCopy(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version, @RequestBody MoveBody body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        String rowId = rowIdOf(version);
        DraftWriteResult result = switch (body.op() == null ? "" : body.op()) {
            case "mv" -> moveFile.move(namespace, name, rowId, body.from(), body.to(), ifMatch,
                    subject).orElseThrow(SkillNotFoundException::new);
            case "cp" -> copyFile.copy(namespace, name, rowId, body.from(), body.to(), ifMatch,
                    subject).orElseThrow(SkillNotFoundException::new);
            default -> throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "op must be \"mv\" or \"cp\"");
        };
        return ResponseEntity.ok(writtenFrom(namespace, result));
    }

    private AuthorVersionResponse.Written writtenFrom(String namespace, DraftWriteResult result) {
        return new AuthorVersionResponse.Written(true,
                AuthorVersionResponse.view(namespace, result.access(), result.version(),
                        handlesOf(result.version())));
    }

    /**
     * A write route's pin: a row id, or the refusal that says why nothing else can be one.
     *
     * <p>A version number and a digest both name content, not a row a caller may edit. Answering
     * the ordinary 404 would say the address is wrong when it is right — the row simply is not a
     * draft — so this is the same {@code version_is_not_draft} the state check gives, raised one
     * step earlier where the pin's shape is still visible.
     */
    private static String rowIdOf(String value) {
        VersionPin pin = pinOf(value);
        if (pin instanceof VersionPin.RowId(String id)) {
            return id;
        }
        throw new com.skillmasterai.common.ConflictException(
                com.skillmasterai.common.ErrorCode.VERSION_IS_NOT_DRAFT,
                "write only applies to a draft; its address is the version code, not a version"
                        + " number or a digest");
    }

    private AuthorVersionResponse.Written written(String namespace, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        // Access, then the row read state-blind: a discard moves the row out of what a `write`
        // caller may see, so the list's visibility filter would turn a successful write into 404.
        // The write path is what decided the caller could do this.
        Access access = readVersion.accessOf(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        VersionSummary row = readVersion.rereadAfterWrite(access, pin)
                .orElseThrow(SkillNotFoundException::new);
        return new AuthorVersionResponse.Written(true,
                AuthorVersionResponse.view(namespace, access, row, handlesOf(row)));
    }

    /**
     * Creates a skill and its first draft in one transaction — the web page's「在线编辑」on the API
     * plane (v10).
     *
     * <p>No namespace in the path: the use case writes the caller's personal namespace, and a path
     * segment would suggest a choice that does not exist.
     */
    @PostMapping
    ResponseEntity<CreatedSkillResponse> createSkill(@RequestBody CreateSkillBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SubmittedSkill created = drafts.record(() -> createSkill.create(body.name(),
                body.description(), body.versionName(), subject));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedSkillResponse(created.namespaceSlug(), created.name(),
                        created.draftId(), created.draftName(), created.state()));
    }

    /**
     * Copies a published version of somebody else's skill into the caller's own namespace (v54,
     * ADR 0173).
     *
     * <p>The path names the <em>source</em>; {@code body.name} is the new skill. A draft pin is
     * refused here even though the author plane accepts one elsewhere: a copy reads a published
     * version, and the CLI spells its pin with the same grammar as every other address.
     */
    @PostMapping(AuthorSkillRoutes.COPY)
    ResponseEntity<CopiedSkillResponse> copy(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CopySkillBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        VersionPin pin = body.version() == null || body.version().isBlank()
                ? new VersionPin.Latest()
                : SkillAddress.pinOf(body.version(), false)
                        .orElseThrow(SkillNotFoundException::new);
        com.skillmasterai.usecase.model.CopiedSkill copied = drafts.record(() -> copySkill
                .copy(namespace, name, body.name(), pin, subject)
                .orElseThrow(SkillNotFoundException::new));
        return ResponseEntity.status(HttpStatus.CREATED).body(CopiedSkillResponse.of(copied));
    }

    /**
     * The base a first write would start from — a reference, not the content.
     *
     * <p>Its rule is the create path's (live → newest visible draft → blank), resolved in one place
     * so the CLI never re-derives "which draft is newest".
     */
    @GetMapping(AuthorSkillRoutes.DRAFT_BASE)
    ResponseEntity<DraftBaseResponse> draftBase(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        CreateDraftFromVersionUseCase.ResolvedBase base = createDraft
                .resolveBaseFor(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new DraftBaseResponse(base.pin() == null ? null
                : new BaseRef(base.pin(), base.digest())));
    }

    /** Creates a draft from a base plus the caller's first change (v10). */
    @PostMapping(AuthorSkillRoutes.VERSIONS)
    ResponseEntity<AuthorDraftResponse> createVersionDraft(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CreateDraftBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        VersionPin from = body.from() == null || body.from().isBlank() ? null
                : SkillAddress.pinOf(body.from(), true)
                        .orElseThrow(() -> new com.skillmasterai.common.RequestException(
                                com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                                "from must name a published version or a draft",
                                List.of(new com.skillmasterai.common.ApiError.Detail("from",
                                        "invalid_format"))));
        DraftEdit edit = body.edit() == null ? null
                : new DraftEdit(body.edit().op(), body.edit().path(), body.edit().text(),
                        body.edit().from(), body.edit().to());
        DraftEditResult result = drafts.record(() -> createDraft.create(namespace, name,
                        new CreateDraftCommand(body.name(), body.note(), from, body.fromDigest(),
                                edit), subject))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(result));
    }

    private AuthorDraftResponse responseOf(DraftEditResult result) {
        // `Map.of()` refuses a null key; a version whose submitter is absent would ask it for one.
        Map<String, String> handles = new HashMap<>();
        if (result.version().submittedBy() != null) {
            handles.putAll(accounts.handlesOf(List.of(result.version().submittedBy())));
        }
        return new AuthorDraftResponse(
                AuthoredSkillResponse.version(result.version(), handles),
                result.manifest().entries().stream()
                        .map(entry -> new AuthoredSkillResponse.File(entry.relpath(),
                                "sha256:" + entry.blobSha256(), entry.size(), entry.isBinary()))
                        .toList());
    }

    private Map<String, String> handlesOf(VersionSummary row) {
        return accounts.handlesOf(List.of(row.submittedBy()));
    }

    private static VersionPin pinOf(String value) {
        return SkillAddress.parse("@" + value, true).orElseThrow(SkillNotFoundException::new).pin();
    }

    /** A body-carried pin is required: an absent one is a usage error, not "latest". */
    private static void requirePin(String version) {
        if (version == null || version.isBlank()) {
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "this action needs the version it acts on, in \"version\"");
        }
    }

    record MoveBody(String op, String from, String to) {
    }

    record NameBody(String name) {
    }

    record NoteBody(String note) {
    }

    /**
     * {@code major} / {@code minor} / {@code patch}, or {@code none} to clear. An unrecognised value
     * is a 400 that names the field (see {@code ReleaseType.canonicalOrNull}).
     */
    record ReleaseTypeBody(String release_type) {
    }

    /** Creating a skill: the two required fields and the first draft's name. */
    record CreateSkillBody(String name, String description, String versionName) {
    }

    record CreatedSkillResponse(String namespace, String name, String versionCode,
            String versionName, String state) {
    }

    /** The base the server would use; {@code base} is null when it would be blank. */
    record DraftBaseResponse(BaseRef base) {
    }

    record BaseRef(String pin, String digest) {
    }

    /** A first change: the draft's labels, the base the caller read, and the edit itself. */
    record CreateDraftBody(String name, String note, String from, String fromDigest, EditBody edit) {
    }

    record EditBody(String op, String path, String text, String from, String to) {
    }

    record AuthorDraftResponse(AuthoredSkillResponse.Version version,
            List<AuthoredSkillResponse.File> files) {
    }
}

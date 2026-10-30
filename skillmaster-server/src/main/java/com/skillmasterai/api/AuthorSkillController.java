package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.ArchiveSkillVersionUseCase;
import com.skillmasterai.usecase.CreateDraftFromVersionUseCase;
import com.skillmasterai.usecase.CreateSkillUseCase;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.CopyDraftFileUseCase;
import com.skillmasterai.usecase.CountVersionUseCase;
import com.skillmasterai.usecase.DeleteDraftFileUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.GrepVersionUseCase;
import com.skillmasterai.usecase.ListAuthorVersionsUseCase;
import com.skillmasterai.usecase.MoveDraftFileUseCase;
import com.skillmasterai.usecase.ReadAuthorVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.UpdateDraftNameUseCase;
import com.skillmasterai.usecase.WriteDraftFileUseCase;
import com.skillmasterai.usecase.UpdateDraftNoteUseCase;
import com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase;
import com.skillmasterai.usecase.model.CountResult;
import com.skillmasterai.usecase.model.CreateDraftCommand;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.usecase.model.DraftEditResult;
import com.skillmasterai.usecase.model.DraftWriteResult;
import com.skillmasterai.usecase.model.GrepResult;
import com.skillmasterai.usecase.model.SubmittedSkill;
import tools.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 */
@RestController
@RequestMapping(path = AuthorSkillRoutes.BASE)
class AuthorSkillController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ArchiveSkillVersionUseCase archiveVersion;
    private final ListAuthorVersionsUseCase listVersions;
    private final ReadAuthorVersionUseCase readVersion;
    private final ReadAuthoredSkillUseCase readAuthored;
    private final DiffAuthoredSkillUseCase diffAuthored;
    private final UpdateDraftNameUseCase updateName;
    private final UpdateDraftNoteUseCase updateNote;
    private final UpdateDraftReleaseTypeUseCase updateReleaseType;
    private final DiscardSkillVersionUseCase discardVersion;
    private final WriteDraftFileUseCase writeFile;
    private final DeleteDraftFileUseCase deleteFile;
    private final MoveDraftFileUseCase moveFile;
    private final CopyDraftFileUseCase copyFile;
    private final GrepVersionUseCase grepVersion;
    private final CountVersionUseCase countVersion;
    private final CreateSkillUseCase createSkill;
    private final CreateDraftFromVersionUseCase createDraft;
    private final DraftRecorder drafts;
    private final AccountDirectory accounts;
    private final ObjectMapper objectMapper;

    AuthorSkillController(ArchiveSkillVersionUseCase archiveVersion,
            ListAuthorVersionsUseCase listVersions, ReadAuthorVersionUseCase readVersion,
            ReadAuthoredSkillUseCase readAuthored, DiffAuthoredSkillUseCase diffAuthored,
            UpdateDraftNameUseCase updateName, UpdateDraftNoteUseCase updateNote,
            UpdateDraftReleaseTypeUseCase updateReleaseType,
            DiscardSkillVersionUseCase discardVersion, WriteDraftFileUseCase writeFile,
            DeleteDraftFileUseCase deleteFile, MoveDraftFileUseCase moveFile,
            CopyDraftFileUseCase copyFile, GrepVersionUseCase grepVersion,
            CountVersionUseCase countVersion, CreateSkillUseCase createSkill,
            CreateDraftFromVersionUseCase createDraft, DraftRecorder drafts,
            AccountDirectory accounts, ObjectMapper objectMapper) {
        this.archiveVersion = archiveVersion;
        this.listVersions = listVersions;
        this.readVersion = readVersion;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.updateName = updateName;
        this.updateNote = updateNote;
        this.discardVersion = discardVersion;
        this.writeFile = writeFile;
        this.deleteFile = deleteFile;
        this.moveFile = moveFile;
        this.copyFile = copyFile;
        this.grepVersion = grepVersion;
        this.countVersion = countVersion;
        this.createSkill = createSkill;
        this.createDraft = createDraft;
        this.updateReleaseType = updateReleaseType;
        this.drafts = drafts;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @GetMapping(AuthorSkillRoutes.VERSIONS)
    ResponseEntity<AuthorVersionResponse.Page> list(@PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        ListAuthorVersionsUseCase.Page page = listVersions
                .list(namespace, name, statuses == null ? List.of() : statuses, q, pageSize, cursor,
                        subject)
                .orElseThrow(SkillNotFoundException::new);

        String rung = page.access().owns() ? com.skillmasterai.modules.skill.GrantRole.ADMIN
                : page.access().rung();
        boolean credentialMayWrite = subject.scopes().contains(Scopes.SKILLS_WRITE);
        return ResponseEntity.ok(new AuthorVersionResponse.Page(
                new AuthorVersionResponse.Skill(namespace, page.access().name(),
                        namespace + "/" + page.access().name(), rung,
                        CredentialPermissions.of(
                                com.skillmasterai.modules.skill.GrantRole.permissionsOf(rung),
                                credentialMayWrite),
                        CredentialPermissions.ofCapabilities(
                                com.skillmasterai.modules.skill.GrantRole.capabilitiesOf(rung),
                                credentialMayWrite)),
                page.versions().stream()
                        .map(row -> AuthorVersionResponse.view(namespace, page.access(), row,
                                page.handles()))
                        .toList(),
                page.counts(),
                page.next()));
    }

    @GetMapping(AuthorSkillRoutes.ONE_VERSION)
    ResponseEntity<AuthorVersionResponse> show(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        ReadAuthorVersionUseCase.View view = readVersion
                .show(namespace, name, pinOf(version), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(AuthorVersionResponse.of(namespace, view.access(),
                view.version(), handlesOf(view.version()),
                SkillMetaResponse.compatibilityOf(view.frontmatterJson(), objectMapper),
                subject.scopes().contains(Scopes.SKILLS_WRITE)));
    }

    @GetMapping(AuthorSkillRoutes.DIFF)
    ResponseEntity<SkillDiffResponse> diff(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String file,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillDiffResponse body = diffAuthored.diff(namespace, name, pinOf(version),
                        from == null ? new VersionPin.Latest() : pinOf(from), file, cursor, subject)
                .map(SkillDiffResponse::of)
                .orElseThrow(SkillNotFoundException::new);
        String etag = SkillDiffResponse.etag(body, file, cursor);
        if (SkillDiffResponse.matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED)
                    .eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(body);
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

    /** The file list of one version — the plain names, no manifest digests. */
    @GetMapping(AuthorSkillRoutes.ONE_VERSION + "/files")
    ResponseEntity<FilesResponse> files(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @AuthenticationPrincipal AuthenticatedSubject subject) {
        // **The rung before the pin.** Resolving first let an `invoke` caller with a draft or a
        // revoked pin reach the pin's 404 instead of this route's 403, and a 404 for "you may not
        // enumerate" tells the caller the wrong thing (v7 TD §安全边界, 验收 #31). The rung here is
        // `browse`: enumerating is the act this route exists for, and it is not one `invoke` may do.
        readVersion.accessOf(namespace, name, subject).orElseThrow(SkillNotFoundException::new);
        var authored = readAuthored.detail(namespace, name, pinOf(version), subject)
                .orElseThrow(SkillNotFoundException::new);
        var selected = authored.selected();
        if (selected == null) {
            throw new SkillNotFoundException();
        }
        // The same bottom rung the single-version read has: `invoke` reaches this page's projection
        // but not the manifest (ADR 0051/0047), and enumerating files is a `browse` act.
        if (!authored.filesVisible()) {
            throw new com.skillmasterai.modules.skill.NotPermittedException(
                    "listing '" + name + "' files needs the `browse` rung");
        }
        return ResponseEntity.ok()
                // The manifest's own version digest, so `ls` and the `write` that follows agree
                // about the baseline without a second request (v7 TD §读响应的头).
                .eTag(FileStreams.etag(selected.manifest().digest()))
                .body(new FilesResponse(selected.manifest().entries().stream()
                        // `sha256:` prefixed, the same spelling every other file row on the wire
                        // uses (v34: `skill stat` prints it, so the listing has to carry it).
                        .map(file -> new FilesResponse.File(file.relpath(),
                                "sha256:" + file.blobSha256(), file.size(), file.isBinary()))
                        .toList()));
    }

    /**
     * One file's bytes, streamed.
     *
     * <p>The response carries the version's digest as {@code ETag} and the manifest's size as
     * {@code Content-Length}; nothing reads the file to produce either (v7 TD §流式与提前退出).
     */
    @GetMapping(AuthorSkillRoutes.FILES)
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> file(
            @PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @PathVariable String relpath,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillDistributionService.StreamLookup lookup = readAuthored
                .streamFile(namespace, name, pinOf(version),
                        CapturedPath.relativeToSkillRoot(relpath), subject)
                .orElseThrow(SkillNotFoundException::new);
        return switch (lookup) {
            case SkillDistributionService.StreamLookup.Found found -> FileStreams.stream(found.file());
            case SkillDistributionService.StreamLookup.NotFoundInManifest missing ->
                    throw new FileNotInManifestException(missing.relpath());
        };
    }

    /**
     * The live version as one zip (ADR 0068).
     *
     * <p>Same rung as "view the files" — resolved by the use case, not here. No {@code If-None-Match}
     * handling: there is no cache in front of this, and the {@code ETag} is for round-trip checking.
     */
    @GetMapping(AuthorSkillRoutes.ARCHIVE)
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> archive(
            @PathVariable String namespace, @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ArchiveStreams.stream(archiveVersion
                .archive(namespace, name, new VersionPin.Latest(), subject)
                .orElseThrow(SkillNotFoundException::new));
    }

    /** Any readable version as one zip, addressed by the same pins the file routes accept. */
    @GetMapping(AuthorSkillRoutes.VERSION_ARCHIVE)
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            versionArchive(@PathVariable String namespace, @PathVariable String name,
                    @PathVariable String version,
                    @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ArchiveStreams.stream(archiveVersion
                .archive(namespace, name, pinOf(version), subject)
                .orElseThrow(SkillNotFoundException::new));
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
        DraftWriteResult result = writeFile
                .write(namespace, name, rowIdOf(version),
                        CapturedPath.relativeToSkillRoot(relpath), bodyOf(request), ifMatch, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(writtenFrom(namespace, result));
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

    /**
     * A scoped search over one version — {@code skill grep} (v7 做 #5; scope widened by ADR 0098).
     *
     * <p><strong>The pin is the ordinary one</strong>: a version name, a digest or a row id, resolved
     * against the caller's visible states at {@code browse}. Grep used to take row ids only, which
     * made "find a line in the published version" impossible without shipping the bytes to the client.
     *
     * <p>Only matches come back; nothing here can return a file the caller did not already name.
     * {@code fixed} asks for a literal (the CLI's {@code -F}); without it the pattern is a regular
     * expression compiled by RE2 (ADR 0066), and a pattern that does not compile is a 400 with
     * {@code bad_pattern} rather than an empty result.
     */
    @GetMapping(AuthorSkillRoutes.ONE_VERSION + "/grep")
    ResponseEntity<GrepResult> grep(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version,
            // **Raw values, not a bound `List`.** Spring splits a `List<String>` parameter on
            // commas, and a comma is ordinary syntax in a pattern (`a{10,101}b`) and in a glob
            // (`*.{md,txt}`): binding one as a list would silently turn one pattern into two. The
            // multi-value map keeps each occurrence exactly as sent.
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(defaultValue = "false") boolean fixed,
            @RequestParam(name = "ignore_case", defaultValue = "false") boolean ignoreCase,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer after,
            @RequestParam(required = false) Integer max,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        List<String> patterns = query.getOrDefault("pattern", List.of());
        List<String> exclude = query.getOrDefault("exclude", List.of());
        GrepResult result = grepVersion
                .grep(namespace, name, pinOf(version),
                        new GrepVersionUseCase.Query(patterns, fixed, ignoreCase, path,
                                before == null ? 0 : before, after == null ? 0 : after,
                                exclude,
                                limit == null ? 0 : limit, max == null ? 0 : max),
                        subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(result);
    }

    /**
     * Counts for one version — {@code skill wc} (v7 做 #4).
     *
     * <p>Every state the caller's rung may read, not drafts only: counting a published version is an
     * ordinary read, and a maintainer choosing between versions wants the same numbers the draft
     * side gives. {@code path} repeats; none means the whole version.
     */
    @GetMapping(AuthorSkillRoutes.ONE_VERSION + "/count")
    ResponseEntity<CountResult> count(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version,
            @RequestParam(required = false) List<String> path,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        CountResult result = countVersion
                .count(namespace, name, pinOf(version), path == null ? List.of() : path, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(result);
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

    /**
     * The file's bytes, read with a hard ceiling rather than as a {@code @RequestBody byte[]}.
     *
     * <p><strong>Why not the framework's binding.</strong> {@code ByteArrayHttpMessageConverter}
     * reads the whole body into memory before the controller runs, and the 16 MiB limit lives in the
     * use case — after the blob has already been allocated. A caller with {@code skills:write} could
     * therefore make the server allocate an arbitrary body by sending one. {@code readNBytes} stops
     * at the ceiling, so the peak allocation is the limit itself, whatever {@code Content-Length}
     * says; a declared length above it is refused before a byte is read.
     *
     * <p>Zero bytes is a legal file, not a missing body, which is why the same helper answers the
     * empty case (v7 做 #10).
     */
    private static byte[] bodyOf(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > IngestLimits.STANDARD_MAX_BYTES) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) IngestLimits.STANDARD_MAX_BYTES + 1);
            if (bytes.length > IngestLimits.STANDARD_MAX_BYTES) {
                throw tooLarge();
            }
            return bytes;
        } catch (IOException e) {
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "the request body could not be read");
        }
    }

    private static IngestException tooLarge() {
        return new IngestException("the file exceeds the per-skill limit of "
                + IngestLimits.STANDARD_MAX_BYTES + " bytes", "files", "too_large");
    }

    record MoveBody(String op, String from, String to) {
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

    record FilesResponse(List<File> files) {
        record File(String relpath, String sha256, long size, boolean isBinary) {
        }
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

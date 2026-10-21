package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.ArchiveSkillVersionUseCase;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.CopyDraftFileUseCase;
import com.skillmasterai.usecase.CountVersionUseCase;
import com.skillmasterai.usecase.DeleteDraftFileUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.GrepDraftUseCase;
import com.skillmasterai.usecase.ListAuthorVersionsUseCase;
import com.skillmasterai.usecase.MoveDraftFileUseCase;
import com.skillmasterai.usecase.ReadAuthorVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.UpdateDraftNameUseCase;
import com.skillmasterai.usecase.WriteDraftFileUseCase;
import com.skillmasterai.usecase.UpdateDraftNoteUseCase;
import com.skillmasterai.usecase.model.CountResult;
import com.skillmasterai.usecase.model.DraftWriteResult;
import com.skillmasterai.usecase.model.GrepResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
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
    private final DiscardSkillVersionUseCase discardVersion;
    private final WriteDraftFileUseCase writeFile;
    private final DeleteDraftFileUseCase deleteFile;
    private final MoveDraftFileUseCase moveFile;
    private final CopyDraftFileUseCase copyFile;
    private final GrepDraftUseCase grepDraft;
    private final CountVersionUseCase countVersion;
    private final AccountDirectory accounts;

    AuthorSkillController(ArchiveSkillVersionUseCase archiveVersion,
            ListAuthorVersionsUseCase listVersions, ReadAuthorVersionUseCase readVersion,
            ReadAuthoredSkillUseCase readAuthored, DiffAuthoredSkillUseCase diffAuthored,
            UpdateDraftNameUseCase updateName, UpdateDraftNoteUseCase updateNote,
            DiscardSkillVersionUseCase discardVersion, WriteDraftFileUseCase writeFile,
            DeleteDraftFileUseCase deleteFile, MoveDraftFileUseCase moveFile,
            CopyDraftFileUseCase copyFile, GrepDraftUseCase grepDraft,
            CountVersionUseCase countVersion, AccountDirectory accounts) {
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
        this.grepDraft = grepDraft;
        this.countVersion = countVersion;
        this.accounts = accounts;
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

        return ResponseEntity.ok(new AuthorVersionResponse.Page(
                new AuthorVersionResponse.Skill(namespace, page.access().name(),
                        namespace + "/" + page.access().name()),
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
                view.version(), handlesOf(view.version())));
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
                        .map(file -> new FilesResponse.File(file.relpath(), file.size(),
                                file.isBinary()))
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
     * A scoped search over one draft — {@code skill grep} (v7 做 #5).
     *
     * <p>Only matches come back; nothing here can return a file the caller did not already name.
     * {@code fixed} asks for a literal (the CLI's {@code -F}); without it the pattern is a regular
     * expression compiled by RE2 (ADR 0066), and a pattern that does not compile is a 400 with
     * {@code bad_pattern} rather than an empty result.
     */
    @GetMapping(AuthorSkillRoutes.ONE_VERSION + "/grep")
    ResponseEntity<GrepResult> grep(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version,
            @RequestParam String pattern,
            @RequestParam(defaultValue = "false") boolean fixed,
            @RequestParam(name = "ignore_case", defaultValue = "false") boolean ignoreCase,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        GrepResult result = grepDraft
                .grep(namespace, name, grepRowIdOf(version), pattern, fixed, ignoreCase, path,
                        limit == null ? 0 : limit, subject)
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
     * grep's pin: a row id, or the one refusal grep can give for anything else.
     *
     * <p>A number or a digest names a published version, and grep is a draft-only scan. Answering the
     * write route's {@code version_is_not_draft} here would tell a reader the wrong story ("write only
     * applies to a draft"), so the named-pin case gets {@code grep_requires_draft} — the same code the
     * state check raises for a row that is no longer a draft, and the one the CLI turns into
     * {@code cat | grep}.
     */
    private static String grepRowIdOf(String value) {
        VersionPin pin = pinOf(value);
        if (pin instanceof VersionPin.RowId(String id)) {
            return id;
        }
        throw new com.skillmasterai.common.ConflictException(
                com.skillmasterai.common.ErrorCode.GREP_REQUIRES_DRAFT,
                "grep only applies to a draft; read a published version's file and grep it locally");
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

    record FilesResponse(List<File> files) {
        record File(String relpath, long size, boolean isBinary) {
        }
    }
}

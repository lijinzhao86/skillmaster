package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.ListAuthorVersionsUseCase;
import com.skillmasterai.usecase.ReadAuthorVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.UpdateDraftNameUseCase;
import com.skillmasterai.usecase.UpdateDraftNoteUseCase;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final ListAuthorVersionsUseCase listVersions;
    private final ReadAuthorVersionUseCase readVersion;
    private final ReadAuthoredSkillUseCase readAuthored;
    private final DiffAuthoredSkillUseCase diffAuthored;
    private final UpdateDraftNameUseCase updateName;
    private final UpdateDraftNoteUseCase updateNote;
    private final DiscardSkillVersionUseCase discardVersion;
    private final AccountDirectory accounts;

    AuthorSkillController(ListAuthorVersionsUseCase listVersions, ReadAuthorVersionUseCase readVersion,
            ReadAuthoredSkillUseCase readAuthored, DiffAuthoredSkillUseCase diffAuthored,
            UpdateDraftNameUseCase updateName, UpdateDraftNoteUseCase updateNote,
            DiscardSkillVersionUseCase discardVersion, AccountDirectory accounts) {
        this.listVersions = listVersions;
        this.readVersion = readVersion;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.updateName = updateName;
        this.updateNote = updateNote;
        this.discardVersion = discardVersion;
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
        return ResponseEntity.ok(new FilesResponse(selected.manifest().entries().stream()
                .map(file -> new FilesResponse.File(file.relpath(), file.size(), file.isBinary()))
                .toList()));
    }

    /** One file's bytes. */
    @GetMapping(AuthorSkillRoutes.FILES)
    ResponseEntity<byte[]> file(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @PathVariable String relpath,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillDistributionService.FileLookup lookup = readAuthored
                .file(namespace, name, pinOf(version), CapturedPath.relativeToSkillRoot(relpath),
                        subject)
                .orElseThrow(SkillNotFoundException::new);
        return switch (lookup) {
            case SkillDistributionService.FileLookup.Found found -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE,
                            MediaTypes.forRelpath(found.file().entry().relpath()))
                    .body(found.file().bytes());
            case SkillDistributionService.FileLookup.NotFoundInManifest missing ->
                    throw new FileNotInManifestException(missing.relpath());
        };
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

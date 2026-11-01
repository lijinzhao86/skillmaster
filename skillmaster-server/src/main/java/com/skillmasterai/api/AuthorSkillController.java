package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.ArchiveSkillVersionUseCase;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.CountVersionUseCase;
import com.skillmasterai.usecase.GrepVersionUseCase;
import com.skillmasterai.usecase.ListAuthorVersionsUseCase;
import com.skillmasterai.usecase.ReadAuthorVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.model.CountResult;
import com.skillmasterai.usecase.model.GrepResult;
import tools.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final GrepVersionUseCase grepVersion;
    private final CountVersionUseCase countVersion;
    private final DraftRecorder drafts;
    private final AccountDirectory accounts;
    private final ObjectMapper objectMapper;

    AuthorSkillController(ArchiveSkillVersionUseCase archiveVersion,
            ListAuthorVersionsUseCase listVersions,
            ReadAuthorVersionUseCase readVersion,
            ReadAuthoredSkillUseCase readAuthored,
            DiffAuthoredSkillUseCase diffAuthored,
            GrepVersionUseCase grepVersion,
            CountVersionUseCase countVersion,
            DraftRecorder drafts,
            AccountDirectory accounts,
            ObjectMapper objectMapper) {
        this.archiveVersion = archiveVersion;
        this.listVersions = listVersions;
        this.readVersion = readVersion;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.grepVersion = grepVersion;
        this.countVersion = countVersion;
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
                                credentialMayWrite),
                        page.access().owns(), page.access().offlineAt() != null,
                        page.access().everPublished()),
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

    private Map<String, String> handlesOf(VersionSummary row) {
        return accounts.handlesOf(List.of(row.submittedBy()));
    }

    private static VersionPin pinOf(String value) {
        return SkillAddress.parse("@" + value, true).orElseThrow(SkillNotFoundException::new).pin();
    }

    record FilesResponse(List<File> files) {
        record File(String relpath, String sha256, long size, boolean isBinary) {
        }
    }

}

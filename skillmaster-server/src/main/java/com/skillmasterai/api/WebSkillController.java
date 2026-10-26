package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.skill.SkillRequestException;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionState;
import com.skillmasterai.usecase.ArchiveSkillVersionUseCase;
import com.skillmasterai.usecase.DecideSkillAccessRequestUseCase;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.ListAuthoredSkillsUseCase;
import com.skillmasterai.usecase.ListSkillAccessRequestsUseCase;
import com.skillmasterai.usecase.ListSkillGrantsUseCase;
import com.skillmasterai.usecase.PublishSkillVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.RequestSkillAccessUseCase;
import com.skillmasterai.usecase.RestoreSkillUseCase;
import com.skillmasterai.usecase.RevokeSkillShareUseCase;
import com.skillmasterai.usecase.ShareSkillUseCase;
import com.skillmasterai.usecase.SoftDeleteSkillUseCase;
import com.skillmasterai.usecase.UpdateSkillVisibilityUseCase;
import com.skillmasterai.usecase.WithdrawSkillAccessRequestUseCase;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * §4.1's browser plane for a skill: the author's reads, and the two writes that change what is live.
 *
 * <p><strong>Publishing exists only here</strong> (ADR 0031). It changes what every agent reading
 * through {@code /api/v1} will get, so it is reached only by a request that carries a session cookie
 * and a double-submit CSRF token — a browser a person is sitting in front of. The CLI submits and
 * then opens this page; it has no way to do this itself, which is the point rather than a gap.
 *
 * <p>The reads are the other half of the same split: they show drafts, which the consumption plane
 * cannot. They carry a session and not a token, and that is what makes them the author's view rather
 * than a wider consumption one — an agent holding a token cannot reach this controller even to read.
 *
 * <p>The caller arrives as Spring's {@link Authentication} and becomes an
 * {@link AuthenticatedSubject#ofSession} — the same id the session was established with, and no
 * scopes, because a session grants none (see {@code WebAuthentication}). Every read of that value
 * here is a {@code getName()}, which is what ADR 0014 fixed the session's principal to be.
 *
 * <p>Thin, like {@link SkillsController}: the decisions are all in the use cases, and what is left is
 * unmarshalling a body and choosing a status. An address that resolves to nothing the caller may act
 * on is §4.1's one 404, raised by the use case returning empty; a version that exists but is in the
 * wrong state is a 400 with its reason, raised by an exception and mapped where the others are.
 */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebSkillController {

    private final ListAuthoredSkillsUseCase listAuthored;
    private final ReadAuthoredSkillUseCase readAuthored;
    private final DiffAuthoredSkillUseCase diffAuthored;
    private final PublishSkillVersionUseCase publishVersion;
    private final DiscardSkillVersionUseCase discardVersion;
    private final com.skillmasterai.usecase.UpdateDraftNameUseCase updateDraftName;
    private final com.skillmasterai.usecase.UpdateDraftNoteUseCase updateDraftNote;
    private final com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase updateReleaseType;
    private final com.skillmasterai.usecase.RevokeVersionUseCase revokeVersion;
    private final com.skillmasterai.usecase.SetSkillOfflineUseCase setOffline;
    private final ShareSkillUseCase shareSkill;
    private final RevokeSkillShareUseCase revokeShare;
    private final ListSkillGrantsUseCase listGrants;
    private final UpdateSkillVisibilityUseCase updateVisibility;
    private final SoftDeleteSkillUseCase softDelete;
    private final RestoreSkillUseCase restore;
    private final RequestSkillAccessUseCase requestAccess;
    private final WithdrawSkillAccessRequestUseCase withdrawAccess;
    private final ListSkillAccessRequestsUseCase listAccessRequests;
    private final DecideSkillAccessRequestUseCase decideAccessRequest;
    private final ArchiveSkillVersionUseCase archiveVersion;
    private final ObjectMapper objectMapper;

    WebSkillController(ListAuthoredSkillsUseCase listAuthored, ReadAuthoredSkillUseCase readAuthored,
            DiffAuthoredSkillUseCase diffAuthored, PublishSkillVersionUseCase publishVersion,
            DiscardSkillVersionUseCase discardVersion, ShareSkillUseCase shareSkill,
            RevokeSkillShareUseCase revokeShare, ListSkillGrantsUseCase listGrants,
            UpdateSkillVisibilityUseCase updateVisibility, SoftDeleteSkillUseCase softDelete,
            RestoreSkillUseCase restore, RequestSkillAccessUseCase requestAccess,
            WithdrawSkillAccessRequestUseCase withdrawAccess,
            ListSkillAccessRequestsUseCase listAccessRequests,
            DecideSkillAccessRequestUseCase decideAccessRequest,
            com.skillmasterai.usecase.UpdateDraftNameUseCase updateDraftName,
            com.skillmasterai.usecase.UpdateDraftNoteUseCase updateDraftNote,
            com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase updateReleaseType,
            com.skillmasterai.usecase.RevokeVersionUseCase revokeVersion,
            com.skillmasterai.usecase.SetSkillOfflineUseCase setOffline,
            ArchiveSkillVersionUseCase archiveVersion, ObjectMapper objectMapper) {
        this.listAuthored = listAuthored;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.publishVersion = publishVersion;
        this.discardVersion = discardVersion;
        this.updateDraftName = updateDraftName;
        this.updateDraftNote = updateDraftNote;
        this.revokeVersion = revokeVersion;
        this.setOffline = setOffline;
        this.shareSkill = shareSkill;
        this.revokeShare = revokeShare;
        this.listGrants = listGrants;
        this.updateVisibility = updateVisibility;
        this.softDelete = softDelete;
        this.restore = restore;
        this.requestAccess = requestAccess;
        this.withdrawAccess = withdrawAccess;
        this.listAccessRequests = listAccessRequests;
        this.decideAccessRequest = decideAccessRequest;
        this.archiveVersion = archiveVersion;
        this.updateReleaseType = updateReleaseType;
        this.objectMapper = objectMapper;
    }

    /**
     * Everything the caller has submitted, drafts included — or the deleted list (ADR 0050).
     *
     * <p>No namespace parameter, and no way to ask for somebody else's: the use case resolves the
     * caller's own namespace, so there is no value here that could name another one. This is the
     * page the CLI's deep link lands on.
     *
     * <p>{@code state} chooses between the two lists. {@code deleted} is not a filter over the live
     * one: every row there can only be restored, and restoring is the owner's, so the list is
     * ownership's answer alone. An unknown value is the caller's to fix, hence a 400 naming it
     * rather than a silent fallback to the live list.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthoredSkillsResponse> list(
            @RequestParam(required = false) String state, Authentication caller) {
        boolean deleted;
        if (state == null || "live".equals(state)) {
            deleted = false;
        } else if ("deleted".equals(state)) {
            deleted = true;
        } else {
            throw new SkillRequestException("state", "must_be_live_or_deleted");
        }
        return ResponseEntity.ok(AuthoredSkillsResponse.of(
                listAuthored.list(AuthenticatedSubject.ofSession(caller.getName()), deleted)));
    }

    /**
     * One skill, with every version it has and the one the address named.
     *
     * <p>The address is parsed exactly as the consumption plane parses it — {@code @3} and
     * {@code @sha256:…} included — because a pinned address means the same thing on both, and a
     * version the author pinned is one they are entitled to read whether or not it is published.
     * {@code latest} resolves to the pointer, or to the most recent submission when nothing is live.
     */
    @GetMapping(path = WebSkillRoutes.SKILL, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthoredSkillResponse> detail(@PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(name = "base", required = false) String base, Authentication caller) {
        SkillAddress address = addressOf(name);
        var subject = AuthenticatedSubject.ofSession(caller.getName());
        var authored = "none".equals(base)
                ? readAuthored.blankDetail(namespace, address.name(), subject)
                : base == null
                        ? readAuthored.detail(namespace, address.name(), address.pin(), subject)
                        : Optional.<com.skillmasterai.usecase.model.AuthoredSkill>empty();
        return authored
                .map(a -> ResponseEntity.ok(AuthoredSkillResponse.of(a, objectMapper)))
                .orElseThrow(() -> base != null && !"none".equals(base)
                        ? new com.skillmasterai.common.RequestException(
                                com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                                "base must be \"none\" when it is given", List.of(
                                        new com.skillmasterai.common.ApiError.Detail("base",
                                                "unknown_value")))
                        : new SkillNotFoundException());
    }

    /** The original {@code SKILL.md} bytes of whichever version the address named. */
    @GetMapping(WebSkillRoutes.BODY)
    ResponseEntity<byte[]> body(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        SkillAddress address = addressOf(name);
        return readAuthored.body(namespace, address.name(), address.pin(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .map(bytes -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                        .body(bytes))
                .orElseThrow(SkillNotFoundException::new);
    }

    /**
     * One file of the version, by exact {@code relpath}.
     *
     * <p>The author plane's L3, and it exists because the page shows a version's file list: every
     * entry but the body has no other way to be read, since the API plane's L3 resolves only
     * published versions. A draft's {@code references/} would otherwise be listed and unopenable.
     *
     * <p>{@code {*relpath}} takes the rest of the path, slashes included, and the value is used as a
     * <strong>lookup key</strong> against the stored manifest — never as a path. Same rule, same
     * reason as {@link SkillsController#file}: it is what makes §4.2's exact-match requirement
     * unfalsifiable rather than merely intended.
     */
    @GetMapping(WebSkillRoutes.FILE)
    ResponseEntity<byte[]> file(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String relpath, Authentication caller) {
        SkillAddress address = addressOf(name);
        SkillDistributionService.FileLookup lookup = readAuthored
                .file(namespace, address.name(), address.pin(),
                        CapturedPath.relativeToSkillRoot(relpath),
                        AuthenticatedSubject.ofSession(caller.getName()))
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

    /**
     * The whole version as one zip (ADR 0068), at the address's own pin.
     *
     * <p>A plain {@code <a href>} target: a session is enough and a GET needs no CSRF token. The
     * page draws no progress and no result — the browser's download is the result, and a second
     * layer in the page would only add a way for it to fail.
     */
    @GetMapping(WebSkillRoutes.ARCHIVE)
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            archive(@PathVariable String namespace, @PathVariable String name,
                    Authentication caller) {
        SkillAddress address = addressOf(name);
        return ArchiveStreams.stream(archiveVersion
                .archive(namespace, address.name(), address.pin(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new));
    }

    /**
     * What changed in one version against another.
     *
     * <p>The version being looked at comes from the address's suffix if it has one and from
     * {@code ?to=} if that is given — the query wins, because it is the more explicit of the two. So
     * {@code /web/skills/demo/pdf-tools@4/diff} means "what does publishing @4 change", and
     * {@code ?from=2&to=4} means what it says. With neither, {@code latest} resolves to the live
     * version.
     *
     * <p>{@code ?from=} is optional on purpose: omitting it compares against what is live, which is
     * the question the page is actually asking. Both parameters are parsed rather than bound to an
     * enum or a pattern — a version number that is not a number is §4.1's 404, from the same place
     * every other unreadable version is, rather than a 400 that would tell a caller which half of the
     * address was wrong.
     */
    @GetMapping(path = WebSkillRoutes.DIFF, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillDiffResponse> diff(@PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication caller) {
        SkillAddress address = addressOf(name);
        SkillDiffResponse body = diffAuthored.diff(namespace, address.name(),
                        to == null ? address.pin() : pinOf(to), pinOf(from), file, cursor,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .map(SkillDiffResponse::of)
                .orElseThrow(SkillNotFoundException::new);
        String etag = SkillDiffResponse.etag(body, file, cursor);
        if (SkillDiffResponse.matches(ifNoneMatch, etag)) {
            // Versions do not change, so the cached answer is the answer. The line diff is not run
            // again: this is the same comparison the caller already has.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    /**
     * A query parameter as a version pin, or {@code latest} when it was not given.
     *
     * <p>Delegates to {@link SkillAddress} by prefixing an {@code @}: what a version looks like is
     * §4.1's rule and is written down once, in the class the path addresses go through. A second
     * parser here would be a second thing to teach the {@code sha256:} spelling to, and the two
     * spellings of one version are exactly the drift that class exists to prevent.
     *
     * <p>So a malformed value is §4.1's 404 rather than a 400 — the same answer an unreadable suffix
     * gets in a path, and for the same reason: telling a caller which half of a version was wrong is
     * a distinction §4.1 does not draw.
     */
    /**
     * The path's address, drafts allowed: this is the author plane, so {@code @draft:<id>} is a
     * reachable version here and not on the consumption plane.
     */
    private static SkillAddress addressOf(String segment) {
        return SkillAddress.parse(segment, true).orElseThrow(SkillNotFoundException::new);
    }

    private static VersionPin pinOf(String parameter) {
        return parameter == null ? new VersionPin.Latest()
                : SkillAddress.parse("@" + parameter, true).orElseThrow(SkillNotFoundException::new)
                        .pin();
    }

    /**
     * A body-carried pin is required on the two POST actions. Without this a missing {@code version}
     * becomes {@code latest} through {@link #pinOf}, which is a different request from the one the
     * caller sent; the web form always supplies it, so an absent one is a client bug worth naming.
     */
    private static void requirePin(String version) {
        if (version == null || version.isBlank()) {
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "this action needs the version it acts on, in \"version\"");
        }
    }

    /**
     * The row as it now reads, for a write's echo. Reads through the page's own visibility rules,
     * so a version this caller may not see is the same 404 the read would give.
     */
    private com.skillmasterai.modules.version.VersionSummary stored(String namespace, String name,
            VersionPin pin, Authentication caller) {
        var authored = readAuthored
                .detail(namespace, name, pin, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return authored.versions().stream()
                .filter(row -> switch (pin) {
                    case VersionPin.RowId(String id) -> row.id().equals(id);
                    case VersionPin.Named(String version) -> version.equals(row.version());
                    case VersionPin.Digest(String digest) -> digest.equals(row.digest());
                    case VersionPin.Latest() -> row.isCurrent();
                })
                .findFirst()
                .orElseThrow(SkillNotFoundException::new);
    }

    /** Makes a version the one consumers get. Any non-discarded version, including an older one. */
    @PostMapping(WebSkillRoutes.PUBLISH)
    ResponseEntity<VersionActionResponse> publish(@PathVariable String namespace,
            @PathVariable String name, @RequestBody VersionChoice choice, Authentication caller) {
        requirePin(choice.version());
        if (choice.note() != null) {
            // The note belongs to the draft's overview and freezes at publish; a publish request
            // that carries one is asking for something this action does not do (TD §发布). Refusing
            // is better than ignoring it: the caller would otherwise believe the note was saved.
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "publishing does not take a note; change it on the draft before publishing");
        }
        PromotionOutcome outcome = publishVersion
                .publish(namespace, name, pinOf(choice.version()), choice.releaseName(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.ok(new VersionActionResponse(choice.version(), outcome.version(),
                outcome.draftName(), VersionState.PUBLISHED, outcome.liveAt(), outcome.changed()));
    }

    /**
     * Throws a draft away.
     *
     * <p>No {@code changed} to report: a discard only ever acts on a draft, so anything that returns
     * at all has written. The version is not deleted — the row and its bytes stay, and it simply stops
     * being offered anywhere — which is why this is a state change rather than a 204.
     */
    @PostMapping(WebSkillRoutes.DISCARD)
    ResponseEntity<VersionActionResponse> discard(@PathVariable String namespace,
            @PathVariable String name, @RequestBody VersionChoice choice, Authentication caller) {
        requirePin(choice.version());
        discardVersion.discard(namespace, name, pinOf(choice.version()),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.ok(new VersionActionResponse(choice.version(), null, null,
                VersionState.DISCARDED, null, true));
    }

    /**
     * Renames a draft (ADR 0055). The name is a label, not an identity: no address moves.
     *
     * <p>A PATCH with the pin in the path rather than a POST with it in the body. The body-rule
     * exists so a POST does not look like a read of one version; a PATCH already says it is a write,
     * and the pin names the sub-resource being replaced.
     */
    @PatchMapping(WebSkillRoutes.VERSION_NAME)
    ResponseEntity<NameResponse> rename(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @RequestBody NameBody body, Authentication caller) {
        VersionPin pin = pinOf(version);
        updateDraftName.rename(namespace, name, pin, body.name(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new NameResponse(stored(namespace, name, pin, caller).draftName()));
    }

    /** Replaces a draft's note; full replacement, frozen once published (ADR 0055). */
    @PatchMapping(WebSkillRoutes.VERSION_NOTE)
    ResponseEntity<NoteResponse> note(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @RequestBody NoteBody body, Authentication caller) {
        VersionPin pin = pinOf(version);
        updateDraftNote.setNote(namespace, name, pin, body.note(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        // The stored value, not the request's: whitespace is folded and an empty note is stored as
        // '' and reads as null (TD §草稿说明). Echoing the body would show the page a string the
        // server did not save.
        return ResponseEntity.ok(new NoteResponse(stored(namespace, name, pin, caller).note()));
    }

    /**
     * Sets or clears the release type a draft declares (ADR 0091).
     *
     * <p>The rung here is {@code write}, not "the submitter or {@code maintain}" — the type changes
     * nothing a consumer sees; it is an input to the next publish.
     */
    @PatchMapping(WebSkillRoutes.VERSION_RELEASE_TYPE)
    ResponseEntity<ReleaseTypeResponse> releaseType(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestBody ReleaseTypeBody body, Authentication caller) {
        VersionPin pin = pinOf(version);
        updateReleaseType.setReleaseType(namespace, name, pin, body.release_type(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        // The stored value, not the request's: "none" is stored as null, and echoing the body would
        // show the page a string the server did not save.
        return ResponseEntity.ok(new ReleaseTypeResponse(
                stored(namespace, name, pin, caller).releaseType()));
    }

    /** Takes a published, non-current version down (ADR 0058). */
    @PostMapping(WebSkillRoutes.VERSION_REVOKE)
    ResponseEntity<VersionActionResponse> revoke(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version,
            @RequestBody(required = false) RevokeBody body, Authentication caller) {
        revokeVersion.revoke(namespace, name, pinOf(version), body == null ? null : body.reason(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new VersionActionResponse(version, null, null,
                VersionState.REVOKED, null, true));
    }

    /** Puts a taken-down version back among the readable ones; it stays history. */
    @PostMapping(WebSkillRoutes.VERSION_RESTORE)
    ResponseEntity<VersionActionResponse> restoreVersion(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String version, Authentication caller) {
        revokeVersion.restore(namespace, name, pinOf(version),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new VersionActionResponse(version, null, null,
                VersionState.PUBLISHED, null, true));
    }

    /** Stops serving the whole skill. Owner only; no version changes state. */
    @PostMapping(WebSkillRoutes.OFFLINE)
    ResponseEntity<OfflineResponse> offline(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        setOffline.set(namespace, name, true, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new OfflineResponse(true));
    }

    /** Resumes serving. */
    @PostMapping(WebSkillRoutes.ONLINE)
    ResponseEntity<OfflineResponse> online(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        setOffline.set(namespace, name, false, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new OfflineResponse(false));
    }

    record NameBody(String name) {
    }

    record NoteBody(String note) {
    }

    record RevokeBody(String reason) {
    }

    record NameResponse(String name) {
    }

    record NoteResponse(String note) {
    }

    /**
     * {@code major} / {@code minor} / {@code patch}, or {@code none} to clear. An unrecognised
     * value is a 400 that names the field (see {@code ReleaseType.canonicalOrNull}).
     */
    record ReleaseTypeBody(String release_type) {
    }

    /** The stored type, or null when none is declared. */
    record ReleaseTypeResponse(String release_type) {
    }

    /**
     * What {@code POST …/access-requests} answers: the rung that is now waiting.
     *
     * <p>Same shape as the API plane's {@code AccessAsked}, deliberately — one fact, one field name,
     * whichever plane asked for it.
     */
    record AccessAskedResponse(String rung) {
    }

    record OfflineResponse(boolean offline) {
    }
    /**
     * Who this skill is shared with — the owner's view (ADR 0034).
     *
     * <p>{@code 403} rather than {@code 404} for a grantee who asks: the skill is in their listing, so
     * its existence is already confirmed and a 404 would be false. This is the same exception the API
     * plane raises, mapped in one place.
     */
    @GetMapping(path = WebSkillRoutes.GRANTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillGrantsResponse> grants(@PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer p,
            Authentication caller) {
        return ResponseEntity.ok(SkillGrantsResponse.of(listGrants
                .grants(namespace, name, q, p, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
    }

    /**
     * Shares the skill, or changes the role it is shared with.
     *
     * <p>{@code 201} even when it moved an existing grant: the caller asked for a state and that state
     * now holds, and the page reloads the list rather than reading the status. A handle nobody has and
     * a role outside the two are the same refusals the API plane gives — the server's, in one place.
     */
    @PostMapping(path = WebSkillRoutes.GRANTS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareResponse> share(@PathVariable String namespace,
            @PathVariable String name, @RequestBody SkillGrantRequest request,
            Authentication caller) {
        ShareSkillUseCase.Granted granted = shareSkill
                .share(namespace, name, request.handle(), request.role(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShareResponse.granted(granted.handle(), granted.role()));
    }

    /**
     * Withdraws a share.
     *
     * <p>{@code 204}, and the same on both planes: withdrawing a grant that is not there is a success,
     * because the state the caller asked for — nobody has this — is the state there is. Which of the
     * two it was is a fact the audit row keeps and the wire does not need; the page re-reads its list.
     */
    @DeleteMapping(WebSkillRoutes.ONE_GRANT)
    ResponseEntity<Void> revoke(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, Authentication caller) {
        revokeShare.revoke(namespace, name, handle, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.noContent().build();
    }

    /**
     * Private, or public at one of the two lowest rungs (ADR 0048).
     *
     * <p>Its own route rather than a field on a metadata {@code PATCH}: the gate is {@code admin},
     * and a route whose only field is this one lets a 403 say precisely which rung is missing.
     */
    @PatchMapping(path = WebSkillRoutes.VISIBILITY,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> visibility(@PathVariable String namespace, @PathVariable String name,
            @RequestBody SkillVisibilityRequest request, Authentication caller) {
        updateVisibility.update(namespace, name, request.visibility(), request.publicRung(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft-deletes the skill. Exists only here, and only for its owner (ADR 0046, ADR 0050).
     *
     * <p>A {@code POST} to an action-shaped route rather than a {@code DELETE} on the resource: the
     * action is reversible and the resource is still there, and the {@code DELETE} shape is the one
     * v4 took off the API plane.
     */
    @PostMapping(WebSkillRoutes.DELETE)
    ResponseEntity<Void> delete(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        softDelete.softDelete(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears a soft delete. The same action the deleted list's restore calls; the address is not a
     * way around ownership (ADR 0050 §决定 5).
     */
    @PostMapping(WebSkillRoutes.RESTORE)
    ResponseEntity<Void> restore(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        restore.restore(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * Asks for a rung. {@code 201}: a request now exists, and nothing else changed (ADR 0052).
     *
     * <p>Reached by anyone who can see the skill; the rung must be above their own, which the use
     * case refuses with a 400 rather than the 403 a capability check would give.
     *
     * <p><strong>The body is not decoration.</strong> {@code {"rung": …}} is what both clients read:
     * the console parses every 2xx as JSON and reported an empty 201 as 「服务暂时不可用」 (2026-10-26),
     * and the CLI takes the rung out of this body. The API plane's {@code POST /api/v1/…/access-requests}
     * has answered with this shape since v10; this route was the one that did not.
     */
    @PostMapping(path = WebSkillRoutes.ACCESS_REQUESTS,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessAskedResponse> requestAccess(@PathVariable String namespace,
            @PathVariable String name, @RequestBody SkillAccessRequest request, Authentication caller) {
        requestAccess.request(namespace, name, request.rung(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AccessAskedResponse(request.rung()));
    }

    /** Withdraws the caller's own request. Idempotent, like withdrawing a share. */
    @DeleteMapping(WebSkillRoutes.MY_ACCESS_REQUEST)
    ResponseEntity<Void> withdrawAccess(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        withdrawAccess.withdraw(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /** The pending requests on this skill — the {@code admin} rung's queue (ADR 0052). */
    @GetMapping(path = WebSkillRoutes.ACCESS_REQUESTS,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillAccessRequestsResponse> accessRequests(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return ResponseEntity.ok(SkillAccessRequestsResponse.of(listAccessRequests
                .requests(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
    }

    /**
     * Approves one request at the rung this call carries — which may differ from the one asked for
     * (ADR 0052 §决定 3). {@code 204}: the request is gone and the grant is the answer.
     */
    @PostMapping(path = WebSkillRoutes.APPROVE_ACCESS_REQUEST,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> approveAccess(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, @RequestBody SkillAccessDecisionRequest request,
            Authentication caller) {
        decideAccessRequest.decide(namespace, name, handle, request.rung(), true,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /** Denies one request: it disappears, nothing is granted. {@code 204} like an approval. */
    @PostMapping(WebSkillRoutes.DENY_ACCESS_REQUEST)
    ResponseEntity<Void> denyAccess(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, Authentication caller) {
        decideAccessRequest.decide(namespace, name, handle, null, false,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }
}

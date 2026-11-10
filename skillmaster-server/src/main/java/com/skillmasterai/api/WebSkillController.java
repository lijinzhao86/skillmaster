package com.skillmasterai.api;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityKinds;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.skill.SkillRequestException;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.ArchiveSkillVersionUseCase;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.ListAuthoredSkillsUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.SkillConsumption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ArchiveSkillVersionUseCase archiveVersion;
    private final ObjectMapper objectMapper;
    private final com.skillmasterai.modules.skill.SourceService sources;
    private final com.skillmasterai.modules.metrics.UsageQuery usage;
    private final ActivityLog activity;

    WebSkillController(ListAuthoredSkillsUseCase listAuthored,
            ReadAuthoredSkillUseCase readAuthored,
            DiffAuthoredSkillUseCase diffAuthored,
            ArchiveSkillVersionUseCase archiveVersion,
            ObjectMapper objectMapper,
            com.skillmasterai.modules.skill.SourceService sources,
            com.skillmasterai.modules.metrics.UsageQuery usage,
            ActivityLog activity) {
        this.listAuthored = listAuthored;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.archiveVersion = archiveVersion;
        this.objectMapper = objectMapper;
        this.sources = sources;
        this.usage = usage;
        this.activity = activity;
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
                .map(a -> {
                    String kind = address.pin() instanceof VersionPin.Latest
                            ? ActivityKinds.SKILL_VIEW : ActivityKinds.VERSION_VIEW;
                    activity.record(new ActivityEvent(subject.userId(), null, kind, a.skillId(),
                            a.namespaceSlug() + "/" + a.name(),
                            a.selected() == null ? null : a.selected().id(),
                            a.selected() == null ? null : a.selected().version(), null,
                            Map.of(), Timestamps.now()));
                    return ResponseEntity.ok(
                            AuthoredSkillResponse.of(a, objectMapper, sources, usage));
                })
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
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> body(
            @PathVariable String namespace, @PathVariable String name, Authentication caller) {
        SkillAddress address = addressOf(name);
        ReadAuthoredSkillUseCase.StreamedFile streamed = readAuthored.streamFile(namespace,
                        address.name(), address.pin(), SkillDistributionService.BODY_RELPATH,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        recordFileRead(streamed.consumption(), SkillDistributionService.BODY_RELPATH, caller);
        return switch (streamed.lookup()) {
            case SkillDistributionService.StreamLookup.Found found -> FileStreams
                    .streamBody(found.file());
            case SkillDistributionService.StreamLookup.NotFoundInManifest missing -> throw
                    new FileNotInManifestException(missing.relpath());
        };
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
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            file(@PathVariable String namespace, @PathVariable String name,
                    @PathVariable String relpath, Authentication caller) {
        SkillAddress address = addressOf(name);
        String path = CapturedPath.relativeToSkillRoot(relpath);
        ReadAuthoredSkillUseCase.StreamedFile streamed = readAuthored
                .streamFile(namespace, address.name(), address.pin(), path,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        recordFileRead(streamed.consumption(), path, caller);
        return switch (streamed.lookup()) {
            case SkillDistributionService.StreamLookup.Found found -> FileStreams.stream(found.file());
            case SkillDistributionService.StreamLookup.NotFoundInManifest missing ->
                    throw new FileNotInManifestException(missing.relpath());
        };
    }

    /**
     * A content preview, not an invocation (v58).
     *
     * <p>The browser opening {@code SKILL.md} is somebody reading the skill, not running it. Only
     * the API's {@code /body} route counts as an invoke; this plane records a read of the file.
     */
    private void recordFileRead(SkillConsumption consumed, String path, Authentication caller) {
        if (consumed == null) {
            return;
        }
        String cut = ActivityTargets.cut(path);
        activity.record(new ActivityEvent(AuthenticatedSubject.ofSession(caller.getName()).userId(),
                consumed.ownerUserId(), ActivityKinds.FILE_READ, consumed.skillId(),
                consumed.address(), consumed.versionId(), consumed.versionLabel(), cut,
                Map.of("path", cut), Timestamps.now()));
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
        var artifact = archiveVersion
                .archive(namespace, address.name(), address.pin(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        activity.record(new ActivityEvent(
                AuthenticatedSubject.ofSession(caller.getName()).userId(), null,
                ActivityKinds.DOWNLOAD, null, namespace + "/" + address.name(), null, null, null,
                Map.of("pinned", !(address.pin() instanceof VersionPin.Latest)), Timestamps.now()));
        return ArchiveStreams.stream(artifact);
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
        String etag = SkillDiffResponse.etag(objectMapper.writeValueAsBytes(body));
        if (SkillDiffResponse.matches(ifNoneMatch, etag)) {
            // The validator is computed over the answer itself, so this really is the same
            // comparison the caller already has — a published version cannot change, and an edited
            // draft produces a different validator rather than a stale 304.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    /**
     * The path's address, drafts allowed: this is the author plane, so {@code @draft:<id>} is a
     * reachable version here and not on the consumption plane.
     */
    private static SkillAddress addressOf(String segment) {
        return SkillAddress.parse(segment, true).orElseThrow(SkillNotFoundException::new);
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
    private static VersionPin pinOf(String parameter) {
        return parameter == null ? new VersionPin.Latest()
                : SkillAddress.parse("@" + parameter, true).orElseThrow(SkillNotFoundException::new)
                        .pin();
    }

}

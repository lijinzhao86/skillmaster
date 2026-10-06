package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionState;
import com.skillmasterai.usecase.DiffAuthoredSkillUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.ListAuthoredSkillsUseCase;
import com.skillmasterai.usecase.PublishSkillVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ObjectMapper objectMapper;

    WebSkillController(ListAuthoredSkillsUseCase listAuthored, ReadAuthoredSkillUseCase readAuthored,
            DiffAuthoredSkillUseCase diffAuthored, PublishSkillVersionUseCase publishVersion,
            DiscardSkillVersionUseCase discardVersion, ObjectMapper objectMapper) {
        this.listAuthored = listAuthored;
        this.readAuthored = readAuthored;
        this.diffAuthored = diffAuthored;
        this.publishVersion = publishVersion;
        this.discardVersion = discardVersion;
        this.objectMapper = objectMapper;
    }

    /**
     * Everything the caller has submitted, drafts included.
     *
     * <p>No parameters at all, and no way to ask for somebody else's: the use case resolves the
     * caller's own namespace, so there is no value here that could name another one. This is the page
     * the CLI's deep link lands on.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthoredSkillsResponse> list(Authentication caller) {
        return ResponseEntity.ok(AuthoredSkillsResponse.of(
                listAuthored.list(AuthenticatedSubject.ofSession(caller.getName()))));
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
            @PathVariable String name, Authentication caller) {
        SkillAddress address = SkillAddress.orNotFound(name);
        return readAuthored.detail(namespace, address.name(), address.pin(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .map(authored -> ResponseEntity.ok(AuthoredSkillResponse.of(authored, objectMapper)))
                .orElseThrow(SkillNotFoundException::new);
    }

    /** The original {@code SKILL.md} bytes of whichever version the address named. */
    @GetMapping(WebSkillRoutes.BODY)
    ResponseEntity<byte[]> body(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        SkillAddress address = SkillAddress.orNotFound(name);
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
        SkillAddress address = SkillAddress.orNotFound(name);
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
            Authentication caller) {
        SkillAddress address = SkillAddress.orNotFound(name);
        return diffAuthored.diff(namespace, address.name(),
                        to == null ? address.pin() : pinOf(to), pinOf(from),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .map(diff -> ResponseEntity.ok(SkillDiffResponse.of(diff)))
                .orElseThrow(SkillNotFoundException::new);
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
                : SkillAddress.orNotFound("@" + parameter).pin();
    }

    /** Makes a version the one consumers get. Any non-discarded version, including an older one. */
    @PostMapping(WebSkillRoutes.PUBLISH)
    ResponseEntity<VersionActionResponse> publish(@PathVariable String namespace,
            @PathVariable String name, @RequestBody VersionChoice choice, Authentication caller) {
        PromotionOutcome outcome = publishVersion
                .publish(namespace, name, choice.number(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.ok(new VersionActionResponse(outcome.number(), VersionState.PUBLISHED,
                outcome.liveAt(), outcome.changed()));
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
        discardVersion.discard(namespace, name, choice.number(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);

        return ResponseEntity.ok(
                new VersionActionResponse(choice.number(), VersionState.DISCARDED, null, true));
    }
}

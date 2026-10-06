package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.search.SearchRequest;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import com.skillmasterai.usecase.ReadSkillUseCase;
import com.skillmasterai.usecase.SearchSkillsUseCase;
import com.skillmasterai.usecase.SoftDeleteSkillUseCase;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * §4.1's routes for a skill: the three read endpoints of §4.2 and the two write endpoints of §4.3.
 *
 * <p>Thin by design: every decision this class could make is already made somewhere that can be
 * tested without HTTP. It resolves the caller, parses an address, reads one part, and picks a status
 * code. The one decision that does live here is §4.1's answer to an address that resolves to nothing
 * — see {@link SkillNotFoundException}.
 *
 * <p>There is <strong>no {@code namespace} parameter</strong> on submit, and that is a decision
 * rather than an omission — see {@link SubmitSkillUseCase}. The same goes for {@code visibility}:
 * it is not offered here because §4.2 gives metadata its own endpoint, and accepting it on a
 * submission would make a private skill public by way of a field nobody was looking at.
 */
@RestController
@RequestMapping(path = SkillRoutes.BASE)
class SkillsController {

    private final SubmitSkillUseCase submitSkill;
    private final SoftDeleteSkillUseCase softDeleteSkill;
    private final ReadSkillUseCase readSkill;
    private final SearchSkillsUseCase searchSkills;
    private final ObjectMapper objectMapper;

    SkillsController(SubmitSkillUseCase submitSkill, SoftDeleteSkillUseCase softDeleteSkill,
            ReadSkillUseCase readSkill, SearchSkillsUseCase searchSkills,
            ObjectMapper objectMapper) {
        this.submitSkill = submitSkill;
        this.softDeleteSkill = softDeleteSkill;
        this.readSkill = readSkill;
        this.searchSkills = searchSkills;
        this.objectMapper = objectMapper;
    }

    /**
     * Search, or browse (§4.2).
     *
     * <p>Every parameter is optional, so a bare {@code GET /api/v1/skills} is a valid request: it lists
     * the caller's own skills newest first. That is the natural first thing a client does, and
     * requiring a query string to do it would be an odd gate.
     *
     * <p>{@code namespace} narrows within what the caller may already see. It is not a bypass, and
     * it cannot be: the ownership predicate is in the query regardless of what this parameter says,
     * so naming someone else's namespace returns nothing rather than their skills.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SearchResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        // sort arrives as text and is parsed rather than bound to the enum: §4.2's values are
        // lowercase, and binding would have made the constant name the wire name — so the
        // documented `sort=recent` would have been an error and `sort=RECENT` would have worked.
        SearchRequest request = new SearchRequest(q, namespace,
                sort == null ? SearchRequest.SortOrder.RELEVANCE : SearchRequest.SortOrder.fromWire(sort),
                limit == null ? SearchRequest.DEFAULT_LIMIT : limit,
                cursor);
        return ResponseEntity.ok(SearchResponse.of(searchSkills.search(request, subject)));
    }

    /**
     * Detail and full manifest, with no content (§4.2 L1).
     *
     * <p>The version is whatever the address asked for — nothing, {@code @3}, or {@code
     * @sha256:…} — and the response reports which one it resolved to, together with a URI per file
     * that already carries it. That is the whole pinning mechanism: a client enters once and then
     * follows those URIs, so an intervening publish cannot swap the content underneath it.
     *
     * <p>404 covers four cases and does not distinguish them: no such skill, a skill in a namespace
     * the caller does not own, a soft-deleted skill, and a version that does not exist. §4.2 requires
     * one answer for all four, because any distinction confirms that something exists.
     */
    @GetMapping(path = SkillRoutes.SKILL, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillDetailResponse> detail(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillAddress address = addressOf(name);
        return readSkill.detail(namespace, address.name(), address.pin(), subject)
                .map(detail -> ResponseEntity.ok(SkillDetailResponse.of(detail, objectMapper)))
                .orElseThrow(SkillNotFoundException::new);
    }

    /**
     * The original {@code SKILL.md} bytes (§4.2 L2).
     *
     * <p>No {@code produces} attribute: the type is fixed but it is not negotiable, and declaring
     * it here would let content negotiation reject a client that asked for something else rather
     * than simply serving the bytes. The body is what the author wrote, and reformatting or
     * re-encoding it to satisfy an {@code Accept} header would break ADR 0005's digest.
     */
    @GetMapping(SkillRoutes.BODY)
    ResponseEntity<byte[]> body(@PathVariable String namespace, @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillAddress address = addressOf(name);
        return readSkill.body(namespace, address.name(), address.pin(), subject)
                .map(bytes -> ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                        .body(bytes))
                .orElseThrow(SkillNotFoundException::new);
    }

    /**
     * One file's original bytes (§4.2 L3).
     *
     * <p>{@code {*relpath}} takes the rest of the path, slashes included, so the value that arrives
     * here is whatever the client sent after being decoded. It is used as a <strong>lookup key</strong>
     * against the stored manifest and never as a path — no normalisation, no resolution, no
     * filesystem. That is what makes §4.2's exact-match rule unfalsifiable rather than merely
     * intended: a name that means something elsewhere simply matches no row, and the answer is 404.
     *
     * <p>Which 404 is §4.1's two: an address that names nothing at all, and a version that resolved
     * without listing this relpath. Only the second is {@link FileNotInManifestException}.
     */
    @GetMapping(SkillRoutes.FILES)
    ResponseEntity<byte[]> file(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String relpath,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillAddress address = addressOf(name);
        SkillDistributionService.FileLookup lookup = readSkill.file(namespace, address.name(),
                        address.pin(), CapturedPath.relativeToSkillRoot(relpath), subject)
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
     * Splits {@code name} into a name and a version pin.
     *
     * <p>A suffix that is not a version is not a 400: §4.1 gives every address that resolves to
     * nothing the same 404, and an address whose version is unreadable is one of those. Rejecting it
     * here would also make it distinguishable from a version that merely does not exist, which is the
     * same leak by another route. The rule itself lives on {@link SkillAddress}.
     */
    private static SkillAddress addressOf(String name) {
        return SkillAddress.orNotFound(name);
    }

    /**
     * Submits one skill from a zip, as a draft.
     *
     * <p><strong>This endpoint cannot publish, and that is the design</strong> (ADR 0031). It
     * records a version nothing may read through this plane until somebody publishes it in a
     * browser — see {@link com.skillmasterai.usecase.PublishSkillVersionUseCase}, which has no route
     * here at all.
     *
     * <p>The whole part is read into memory before validation. That is bounded — {@code
     * spring.servlet.multipart.max-file-size} rejects an oversized body before this method runs —
     * and it is required by ADR 0005: the digest is taken over the bytes exactly as received, so
     * anything that streams them through a file or a buffer that could rewrite them is a
     * correctness bug, not an optimisation.
     *
     * <p>200 rather than 201 when the content already existed. The distinct status is the only
     * signal a client needs to tell a first submission from an idempotent replay; making it also
     * compare digests would push a rule the server already knows onto every caller.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubmitResponse> submit(@RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        byte[] zip;
        try {
            zip = file.getBytes();
        } catch (IOException e) {
            // The part was announced but could not be read off the wire — a truncated upload,
            // not a validation failure. Reported as a bad upload rather than a 500 because the
            // only party who can fix it is the caller.
            throw new IngestException("the uploaded file could not be read", "file", "unreadable");
        }

        SubmittedSkill submitted = submitSkill.submit(zip, subject);
        return ResponseEntity
                .status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SubmitResponse.of(submitted));
    }

    /**
     * Soft-deletes a skill (§4.3).
     *
     * <p>404 for "not yours" as well as "not there" — the two are indistinguishable by design, so
     * that a delete cannot be used to probe for the existence of another user's private skill.
     *
     * <p>The name is <strong>not</strong> parsed for a version suffix, unlike on the read endpoints:
     * §4.3 says a write acts on the skill, and a version is produced or moved by the operation rather
     * than named by the caller. So {@code demo/pinned@2} is looked up as the literal name
     * {@code pinned@2}, which no skill can hold — M5 reserves the character — and the answer is the
     * same 404 as any other address naming nothing.
     */
    @DeleteMapping(SkillRoutes.SKILL)
    ResponseEntity<Void> delete(@PathVariable String namespace, @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        softDeleteSkill.softDelete(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }
}

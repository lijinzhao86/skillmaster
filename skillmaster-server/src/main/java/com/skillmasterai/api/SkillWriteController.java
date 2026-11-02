package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.ListSkillGrantsUseCase;
import com.skillmasterai.usecase.ReadSkillAccessRequestUseCase;
import com.skillmasterai.usecase.RequestSkillAccessUseCase;
import com.skillmasterai.usecase.RevokeSkillShareUseCase;
import com.skillmasterai.usecase.ShareSkillUseCase;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import com.skillmasterai.usecase.SubmitSkillVersionUseCase;
import com.skillmasterai.usecase.WithdrawSkillAccessRequestUseCase;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.io.IOException;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * §4.1's routes for a skill: the read endpoints of §4.2 and the write endpoints of §4.3.
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
class SkillWriteController {

    private final SubmitSkillUseCase submitSkill;
    private final SubmitSkillVersionUseCase submitVersion;
    private final ShareSkillUseCase shareSkill;
    private final RevokeSkillShareUseCase revokeShare;
    private final ListSkillGrantsUseCase listGrants;
    private final RequestSkillAccessUseCase requestAccess;
    private final WithdrawSkillAccessRequestUseCase withdrawAccess;
    private final ReadSkillAccessRequestUseCase readAccess;
    private final DraftRecorder drafts;

    SkillWriteController(SubmitSkillUseCase submitSkill,
            SubmitSkillVersionUseCase submitVersion,
            ShareSkillUseCase shareSkill,
            RevokeSkillShareUseCase revokeShare,
            ListSkillGrantsUseCase listGrants,
            RequestSkillAccessUseCase requestAccess,
            WithdrawSkillAccessRequestUseCase withdrawAccess,
            ReadSkillAccessRequestUseCase readAccess,
            DraftRecorder drafts) {
        this.submitSkill = submitSkill;
        this.submitVersion = submitVersion;
        this.shareSkill = shareSkill;
        this.revokeShare = revokeShare;
        this.listGrants = listGrants;
        this.requestAccess = requestAccess;
        this.withdrawAccess = withdrawAccess;
        this.readAccess = readAccess;
        this.drafts = drafts;
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
            @RequestPart(value = "name", required = false) String draftName,
            @RequestPart(value = "note", required = false) String note,
            @RequestPart(value = "release_type", required = false) String releaseType,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        requirePackageFitsInMemory(file);
        byte[] zip;
        try {
            zip = file.getBytes();
        } catch (IOException e) {
            // The part was announced but could not be read off the wire — a truncated upload,
            // not a validation failure. Reported as a bad upload rather than a 500 because the
            // only party who can fix it is the caller.
            throw new IngestException("the uploaded file could not be read", "file", "unreadable");
        }

        SubmittedSkill submitted = drafts.record(
                () -> submitSkill.submit(zip, draftName, note, releaseType, subject));
        return ResponseEntity
                .status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SubmitResponse.of(submitted));
    }

    /**
     * Adds a version to a skill that already exists (§4.3, ADR 0034).
     *
     * <p>The route an {@code editor} grant is for. It carries no version suffix for the same reason
     * every write address here does not: the address names the skill, and the version is what the
     * operation produces.
     */
    @PostMapping(path = SkillRoutes.VERSIONS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubmitResponse> submitVersion(@PathVariable String namespace,
            @PathVariable String name, @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String draftName,
            @RequestPart(value = "note", required = false) String note,
            @RequestPart(value = "release_type", required = false) String releaseType,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        requirePackageFitsInMemory(file);
        byte[] zip;
        try {
            zip = file.getBytes();
        } catch (IOException e) {
            throw new IngestException("the uploaded file could not be read", "file", "unreadable");
        }

        SubmittedSkill submitted = drafts.record(() -> submitVersion.submitVersion(namespace, name,
                        zip, draftName, note, releaseType, subject))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity
                .status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SubmitResponse.of(submitted));
    }

    /**
     * Shares this skill, or changes the role it is shared with (§4.3, ADR 0034).
     *
     * <p>The only route in this system that answers 403, and only for a caller who can already read
     * the skill — see {@link com.skillmasterai.modules.skill.NotPermittedException}.
     */
    @PostMapping(path = SkillRoutes.GRANTS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareResponse> share(@PathVariable String namespace, @PathVariable String name,
            @RequestBody SkillGrantRequest request, @AuthenticationPrincipal AuthenticatedSubject
            subject) {
        ShareSkillUseCase.Granted granted = shareSkill
                .share(namespace, name, request.handle(), request.role(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShareResponse.granted(granted.handle(), granted.role()));
    }

    /**
     * Withdraws a share (§4.3, ADR 0034).
     *
     * <p>204 whether or not a grant was there to remove: the request says "this person must no
     * longer have access", and that state holds either way. The body would have said which it was,
     * and 204 is what this route answers with — the page reloads the list anyway.
     */
    @DeleteMapping(SkillRoutes.ONE_GRANT)
    ResponseEntity<Void> revoke(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, @AuthenticationPrincipal AuthenticatedSubject subject) {
        revokeShare.revoke(namespace, name, handle, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /** Who this skill is shared with — the owner's view (ADR 0034). */
    @GetMapping(path = SkillRoutes.GRANTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillGrantsResponse> grants(@PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer p,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(SkillGrantsResponse.of(listGrants
                .grants(namespace, name, q, p, subject)
                .orElseThrow(SkillNotFoundException::new)));
    }

    /** Asks for a rung on a skill the caller can see (ADR 0052). Grants nothing until approved. */
    @PostMapping(path = SkillRoutes.ACCESS_REQUESTS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessAsked> askForAccess(@PathVariable String namespace,
            @PathVariable String name, @RequestBody AccessAsk body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        requestAccess.request(namespace, name, body.rung(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AccessAsked(body.rung()));
    }

    /** What I am waiting for on this skill; null means nothing is pending. */
    @GetMapping(path = SkillRoutes.MY_ACCESS_REQUEST, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessStatus> myAccessRequest(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        java.util.Optional<String> pending = readAccess.pending(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new AccessStatus(pending.orElse(null)));
    }

    /** Withdraws my own pending request — idempotent, like withdrawing a share. */
    @DeleteMapping(SkillRoutes.MY_ACCESS_REQUEST)
    ResponseEntity<Void> withdrawAccessRequest(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        withdrawAccess.withdraw(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * The zip import still holds every entry in memory; this is the ceiling that keeps an
     * over-large package from becoming an out-of-memory error instead of an answer (v39).
     */
    private static void requirePackageFitsInMemory(
            org.springframework.web.multipart.MultipartFile file) {
        long ceiling = com.skillmasterai.modules.ingest.IngestLimits.PACKAGE_IN_MEMORY_MAX_BYTES;
        if (file.getSize() > ceiling) {
            throw new IngestException(
                    "the package is " + com.skillmasterai.modules.ingest.IngestLimits
                            .mib(file.getSize()) + ", above the "
                            + com.skillmasterai.modules.ingest.IngestLimits.mib(ceiling)
                            + " import ceiling; upload its files individually",
                    java.util.List.of(new com.skillmasterai.common.ApiError.Detail(
                            "file", "package_too_large", file.getSize(), ceiling)));
        }
    }

    record AccessAsk(String rung) {
    }

    record AccessAsked(String rung) {
    }

    record AccessStatus(String pendingRung) {
    }
}

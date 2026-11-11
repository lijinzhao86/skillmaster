package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionState;
import com.skillmasterai.usecase.ChangeSkillGrantRoleUseCase;
import com.skillmasterai.usecase.DiscardSkillVersionUseCase;
import com.skillmasterai.usecase.ListSkillGrantsUseCase;
import com.skillmasterai.usecase.PublishSkillVersionUseCase;
import com.skillmasterai.usecase.ReadAuthoredSkillUseCase;
import com.skillmasterai.usecase.RestoreSkillUseCase;
import com.skillmasterai.usecase.RevokeSkillShareUseCase;
import com.skillmasterai.usecase.SetSkillOfflineUseCase;
import com.skillmasterai.usecase.SoftDeleteSkillUseCase;
import com.skillmasterai.usecase.UpdateSkillVisibilityUseCase;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
class WebSkillLifecycleController {

    private final ReadAuthoredSkillUseCase readAuthored;
    private final PublishSkillVersionUseCase publishVersion;
    private final DiscardSkillVersionUseCase discardVersion;
    private final com.skillmasterai.usecase.UpdateDraftNameUseCase updateDraftName;
    private final com.skillmasterai.usecase.UpdateDraftNoteUseCase updateDraftNote;
    private final com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase updateReleaseType;
    private final com.skillmasterai.usecase.RevokeVersionUseCase revokeVersion;
    private final com.skillmasterai.usecase.SetSkillOfflineUseCase setOffline;
    private final RevokeSkillShareUseCase revokeShare;
    private final ListSkillGrantsUseCase listGrants;
    private final ChangeSkillGrantRoleUseCase changeRole;
    private final UpdateSkillVisibilityUseCase updateVisibility;
    private final SoftDeleteSkillUseCase softDelete;
    private final RestoreSkillUseCase restore;

    WebSkillLifecycleController(ReadAuthoredSkillUseCase readAuthored,
            PublishSkillVersionUseCase publishVersion,
            DiscardSkillVersionUseCase discardVersion,
            RevokeSkillShareUseCase revokeShare,
            ListSkillGrantsUseCase listGrants,
            ChangeSkillGrantRoleUseCase changeRole,
            UpdateSkillVisibilityUseCase updateVisibility,
            SoftDeleteSkillUseCase softDelete,
            RestoreSkillUseCase restore,
            com.skillmasterai.usecase.UpdateDraftNameUseCase updateDraftName,
            com.skillmasterai.usecase.UpdateDraftNoteUseCase updateDraftNote,
            com.skillmasterai.usecase.UpdateDraftReleaseTypeUseCase updateReleaseType,
            com.skillmasterai.usecase.RevokeVersionUseCase revokeVersion,
            com.skillmasterai.usecase.SetSkillOfflineUseCase setOffline) {
        this.readAuthored = readAuthored;
        this.publishVersion = publishVersion;
        this.discardVersion = discardVersion;
        this.updateDraftName = updateDraftName;
        this.updateDraftNote = updateDraftNote;
        this.revokeVersion = revokeVersion;
        this.setOffline = setOffline;
        this.revokeShare = revokeShare;
        this.listGrants = listGrants;
        this.changeRole = changeRole;
        this.updateVisibility = updateVisibility;
        this.softDelete = softDelete;
        this.restore = restore;
        this.updateReleaseType = updateReleaseType;
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
        SetSkillOfflineUseCase.State state = setOffline
                .set(namespace, name, true, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new OfflineResponse(state.offline(), state.pendingUntil()));
    }

    /** Resumes serving, or cancels a 下线中 ramp. */
    @PostMapping(WebSkillRoutes.ONLINE)
    ResponseEntity<OfflineResponse> online(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        SetSkillOfflineUseCase.State state = setOffline
                .set(namespace, name, false, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new OfflineResponse(state.offline(), state.pendingUntil()));
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

    /** Moves an existing collaborator down; raising is the invitation's job (v45). */
    @PostMapping(path = WebSkillRoutes.GRANT_ROLE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GrantRoleResponse> changeGrantRole(@PathVariable String namespace,
            @PathVariable String name, @PathVariable String handle,
            @RequestBody GrantRoleRequest request, Authentication caller) {
        return ResponseEntity.ok(GrantRoleResponse.of(changeRole
                .change(namespace, name, handle, request.role(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
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
    ResponseEntity<OrganizationResponses.Restored> restore(@PathVariable String namespace,
            @PathVariable String name, @RequestBody(required = false) RestoreBody body,
            Authentication caller) {
        // v61 (ADR 0183): the tombstone may come back under a freed name when a live skill has
        // taken the original. The page has to say which address it now answers to. The optional
        // `id` is the deleted list's row — several tombstones can share a name, and the reader
        // clicked one of them.
        com.skillmasterai.modules.skill.SkillMetadataService.Restored restored = restore
                .restore(namespace, name, body == null ? null : body.id(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new OrganizationResponses.Restored(
                namespace + "/" + restored.name(), restored.renamed()));
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

    record OfflineResponse(boolean offline, String pendingUntil) {
    }

    /** The tombstone to restore, when the deleted list knows which row was clicked. */
    record RestoreBody(String id) {
    }

}

package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.BindCredentialUseCase;
import com.skillmasterai.usecase.CreateSecretUseCase;
import com.skillmasterai.usecase.DeleteSecretUseCase;
import com.skillmasterai.usecase.IncomingSecretBindingsUseCase;
import com.skillmasterai.usecase.ListSecretSharesUseCase;
import com.skillmasterai.usecase.ListSecretsUseCase;
import com.skillmasterai.usecase.RotateSecretValueUseCase;
import com.skillmasterai.usecase.SetSecretQuotaUseCase;
import com.skillmasterai.usecase.UnbindCredentialUseCase;
import com.skillmasterai.usecase.UnshareSecretUseCase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console/CLI credential vault (v52).
 *
 * <p>Thin, like its neighbours: ownership, skill access, the binding rule and the quota all live
 * in use cases. What is left here is parsing a body and choosing a status.
 *
 * <p><strong>No response on this plane carries a value except the lease</strong>, which has its own
 * controller and {@code no-store}. A list that accidentally included the ciphertext would still
 * look like a list; the tests assert the absence.
 */
@RestController
@RequestMapping(path = "/api/v1/secrets")
class SecretsController {

    private final CreateSecretUseCase create;
    private final ListSecretsUseCase list;
    private final RotateSecretValueUseCase rotate;
    private final DeleteSecretUseCase delete;
    private final SetSecretQuotaUseCase setQuota;
    private final BindCredentialUseCase bind;
    private final UnbindCredentialUseCase unbind;
    private final ListSecretSharesUseCase listShares;
    private final UnshareSecretUseCase unshare;
    private final IncomingSecretBindingsUseCase incomingBindings;

    SecretsController(CreateSecretUseCase create, ListSecretsUseCase list,
            RotateSecretValueUseCase rotate, DeleteSecretUseCase delete,
            SetSecretQuotaUseCase setQuota, BindCredentialUseCase bind,
            UnbindCredentialUseCase unbind, ListSecretSharesUseCase listShares,
            UnshareSecretUseCase unshare, IncomingSecretBindingsUseCase incomingBindings) {
        this.create = create;
        this.list = list;
        this.rotate = rotate;
        this.delete = delete;
        this.setQuota = setQuota;
        this.bind = bind;
        this.unbind = unbind;
        this.listShares = listShares;
        this.unshare = unshare;
        this.incomingBindings = incomingBindings;
    }

    /** One value, and its first binding. {@code value} is read but never echoed. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CreateResponse> create(@RequestBody CreateRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        CreateSecretUseCase.Result result = create
                .create(body.name(), body.value(), body.skill(), body.env(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateResponse(
                result.id(), result.name(), result.perDay(), result.used(), result.createdAt(),
                result.bindings().stream()
                        .map(row -> new BindingView(row.skill(), row.env()))
                        .toList()));
    }

    /** The owner's credentials; {@code skill} is an optional {@code namespace/name} filter. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ListResponse> list(@RequestParam(required = false) String skill,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        ListSecretsUseCase.Result result = list.list(skill, subject);
        return ResponseEntity.ok(new ListResponse(result.items().stream()
                .map(item -> new ListItem(item.id(), item.name(), item.perDay(), item.used(),
                        item.createdAt(), item.rotatedAt(), item.lastUsedAt(),
                        item.bindings().stream()
                                .map(row -> new BindingView(row.skill(), row.env()))
                                .toList()))
                .toList()));
    }

    /**
     * What this caller receives from other people's credentials on one skill.
     *
     * <p>Its own address rather than a field on the listing: the listing is rooted in
     * {@code owner_user_id = me}, and folding these rows into it would make one list mean two
     * different ownerships.
     */
    @GetMapping(path = "/incoming", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IncomingResponse> incoming(@RequestParam String skill,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        IncomingSecretBindingsUseCase.Result result = incomingBindings.list(skill, subject);
        return ResponseEntity.ok(new IncomingResponse(result.items().stream()
                .map(row -> new IncomingItem(row.credentialId(), row.name(), row.owner(),
                        row.skill(), row.env(), row.perDay(), row.used()))
                .toList()));
    }

    /** Replaces the value; bindings and shares are untouched. */
    @PutMapping(path = "/{id}/value", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Rotated> rotate(@PathVariable String id, @RequestBody ValueRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        RotateSecretValueUseCase.Result result = rotate.rotate(id, body.value(), subject);
        return ResponseEntity.ok(new Rotated(result.id(), result.rotatedAt()));
    }

    /** Hard delete; bindings and shares go with the row. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        delete.delete(id, subject);
        return ResponseEntity.noContent().build();
    }

    /** The daily lease ceiling. */
    @PatchMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<QuotaChanged> quota(@PathVariable String id, @RequestBody QuotaRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SetSecretQuotaUseCase.Result result = setQuota.set(id, body.perDay(), subject);
        return ResponseEntity.ok(new QuotaChanged(result.id(), result.perDay()));
    }

    /** Exposes the credential to one more skill and environment variable. */
    @PostMapping(path = "/{id}/bindings", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BindingView> bind(@PathVariable String id, @RequestBody BindingRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        BindCredentialUseCase.Result result = bind
                .bind(id, body.skill(), body.env(), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BindingView(result.skill(), result.env()));
    }

    /** Removes one binding; the value stays. */
    @DeleteMapping("/{id}/bindings")
    ResponseEntity<Void> unbind(@PathVariable String id, @RequestParam String skill,
            @RequestParam String env, @AuthenticationPrincipal AuthenticatedSubject subject) {
        if (!unbind.unbind(id, skill, env, subject)) {
            throw new SkillNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /** Who is receiving this credential, and through which skill and variable. */
    @GetMapping(path = "/{id}/shares", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharesResponse> shares(@PathVariable String id,
            @RequestParam(required = false) String skill,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(sharesResponse(listShares.list(id, skill, subject)));
    }

    /**
     * **There is no POST here, on purpose.**
     *
     * <p>Creating a share gives another person a value they can extract, and ADR 0081's first
     * exception puts that in the product's riskier half: it is created on the console's plane only
     * ({@link WebSecretsController}), where a session and a CSRF token say a person is doing it and
     * the page has shown them the sentence. {@code POST /api/v1/secrets/{id}/shares} therefore
     * answers 405 — reaching this path with the wrong method is the API telling a client that the
     * action moved, not that it never existed.
     *
     * <p>Reading and revoking stay: {@link #shares} serves {@code skmr secret shares} and
     * {@link #unshare} serves {@code skmr secret unshare}, both of which are not the risky
     * direction.
     */

    /** Withdraws the share; the next lease is already without it. */
    @DeleteMapping("/{id}/shares")
    ResponseEntity<Void> unshare(@PathVariable String id, @RequestParam String skill,
            @RequestParam String user, @AuthenticationPrincipal AuthenticatedSubject subject) {
        if (!unshare.unshare(id, skill, user, subject)) {
            throw new SkillNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private static SharesResponse sharesResponse(ListSecretSharesUseCase.Result result) {
        return new SharesResponse(result.items().stream()
                .map(row -> new ShareItem(row.user(), row.skill(), row.env(), row.createdAt()))
                .toList());
    }

    /* ---- wire shapes: snake_case by the global Jackson strategy ---- */

    record CreateRequest(String name, String value, String skill, String env) {
    }

    record ValueRequest(String value) {
    }

    record QuotaRequest(int perDay) {
    }

    record BindingRequest(String skill, String env) {
    }

    record BindingView(String skill, String env) {
    }

    record CreateResponse(String id, String name, int perDay, int used, String createdAt,
            List<BindingView> bindings) {
        CreateResponse {
            bindings = List.copyOf(bindings);
        }
    }

    record ListItem(String id, String name, int perDay, int used, String createdAt,
            String rotatedAt, String lastUsedAt, List<BindingView> bindings) {
        ListItem {
            bindings = List.copyOf(bindings);
        }
    }

    record ListResponse(List<ListItem> items) {
        ListResponse {
            items = List.copyOf(items);
        }
    }

    record Rotated(String id, String rotatedAt) {
    }

    record QuotaChanged(String id, int perDay) {
    }

    record ShareItem(String user, String skill, String env, String createdAt) {
    }

    /**
     * One variable received from somebody else's credential.
     *
     * @param owner a handle
     * @param perDay the credential's daily ceiling, and {@code used} today's count across everybody
     *               it serves — the skill pane draws the same five columns for every row
     */
    record IncomingItem(String credentialId, String name, String owner, String skill, String env,
            int perDay, int used) {
    }

    record IncomingResponse(List<IncomingItem> items) {
        IncomingResponse {
            items = List.copyOf(items);
        }
    }

    record SharesResponse(List<ShareItem> items) {
        SharesResponse {
            items = List.copyOf(items);
        }
    }
}

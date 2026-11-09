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
import com.skillmasterai.usecase.ShareSecretUseCase;
import com.skillmasterai.usecase.UnbindCredentialUseCase;
import com.skillmasterai.usecase.UnshareSecretUseCase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * The console's half of the credential vault (v52 P2): the same operations as
 * {@link SecretsController}, authenticated by a browser session instead of an API key.
 *
 * <p><strong>Why a second controller rather than letting the console use the first one.</strong> A
 * session and an API key are different credentials with different lifetimes (ADR 0040), and the
 * console cannot present a bearer token — it holds a cookie and a CSRF token. Serving the console
 * from the API plane would mean teaching that plane to accept a cookie, which is the one thing its
 * statelessness is for.
 *
 * <p><strong>The share write lives here and nowhere else.</strong> ADR 0081's first exception is
 * about giving a person a value they can extract; the product asks the owner to do that on the page,
 * so `POST /api/v1/secrets/{id}/shares` was removed in this version (it answers 405). Every other
 * operation on this plane has its API-plane twin, because the CLI is the first client and must be
 * able to do it from a terminal. Revoking a share stayed on both: taking a grant back is not the
 * risky direction.
 *
 * <p>Thin, like {@link WebKeyController}: the rules are in the use cases, which are the same ones
 * the API plane calls — a second copy of the authorization would be a second answer.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebSecretsController {

    private final CreateSecretUseCase create;
    private final ListSecretsUseCase list;
    private final RotateSecretValueUseCase rotate;
    private final DeleteSecretUseCase delete;
    private final SetSecretQuotaUseCase setQuota;
    private final BindCredentialUseCase bind;
    private final UnbindCredentialUseCase unbind;
    private final ListSecretSharesUseCase listShares;
    private final ShareSecretUseCase share;
    private final UnshareSecretUseCase unshare;
    private final IncomingSecretBindingsUseCase incomingBindings;

    WebSecretsController(CreateSecretUseCase create, ListSecretsUseCase list,
            RotateSecretValueUseCase rotate, DeleteSecretUseCase delete,
            SetSecretQuotaUseCase setQuota, BindCredentialUseCase bind,
            UnbindCredentialUseCase unbind, ListSecretSharesUseCase listShares,
            ShareSecretUseCase share, UnshareSecretUseCase unshare,
            IncomingSecretBindingsUseCase incomingBindings) {
        this.create = create;
        this.list = list;
        this.rotate = rotate;
        this.delete = delete;
        this.setQuota = setQuota;
        this.bind = bind;
        this.unbind = unbind;
        this.listShares = listShares;
        this.share = share;
        this.unshare = unshare;
        this.incomingBindings = incomingBindings;
    }

    /** One value and its first binding. {@code value} is read and never echoed — on this plane too. */
    @PostMapping(path = WebRoutes.SECRETS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.CreateResponse> create(@RequestBody CreateRequest body,
            Authentication caller) {
        CreateSecretUseCase.Result result = create
                .create(body.name(), body.value(), body.skill(), body.env(), subject(caller))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SecretsController.CreateResponse(
                result.id(), result.name(), result.perDay(), result.used(), result.createdAt(),
                result.bindings().stream()
                        .map(row -> new SecretsController.BindingView(row.skill(), row.env()))
                        .toList()));
    }

    /** The signed-in person's credentials; {@code skill} is an optional filter. */
    @GetMapping(path = WebRoutes.SECRETS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.ListResponse> list(
            @RequestParam(required = false) String skill, Authentication caller) {
        ListSecretsUseCase.Result result = list.list(skill, subject(caller));
        return ResponseEntity.ok(new SecretsController.ListResponse(result.items().stream()
                .map(item -> new SecretsController.ListItem(item.id(), item.name(), item.perDay(),
                        item.used(), item.createdAt(), item.rotatedAt(), item.lastUsedAt(),
                        item.bindings().stream()
                                .map(row -> new SecretsController.BindingView(row.skill(), row.env()))
                                .toList()))
                .toList()));
    }

    /** What this person receives from other people's credentials on one skill (the pane's rows). */
    @GetMapping(path = WebRoutes.SECRETS + "/incoming", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.IncomingResponse> incoming(@RequestParam String skill,
            Authentication caller) {
        IncomingSecretBindingsUseCase.Result result = incomingBindings.list(skill, subject(caller));
        return ResponseEntity.ok(new SecretsController.IncomingResponse(result.items().stream()
                .map(row -> new SecretsController.IncomingItem(row.credentialId(), row.name(),
                        row.owner(), row.skill(), row.env(), row.perDay(), row.used()))
                .toList()));
    }

    /** Replaces the value; bindings and shares are untouched. */
    @PutMapping(path = WebRoutes.SECRET_BY_ID + "/value",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.Rotated> rotate(@PathVariable String id,
            @RequestBody ValueRequest body, Authentication caller) {
        RotateSecretValueUseCase.Result result = rotate.rotate(id, body.value(), subject(caller));
        return ResponseEntity.ok(new SecretsController.Rotated(result.id(), result.rotatedAt()));
    }

    /** Hard delete; the console asks first (ADR 0056), this does not re-ask. */
    @DeleteMapping(WebRoutes.SECRET_BY_ID)
    ResponseEntity<Void> delete(@PathVariable String id, Authentication caller) {
        delete.delete(id, subject(caller));
        return ResponseEntity.noContent().build();
    }

    /** The daily lease ceiling, adjusted in place on the list row. */
    @PatchMapping(path = WebRoutes.SECRET_BY_ID, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.QuotaChanged> quota(@PathVariable String id,
            @RequestBody QuotaRequest body, Authentication caller) {
        SetSecretQuotaUseCase.Result result = setQuota.set(id, body.perDay(), subject(caller));
        return ResponseEntity.ok(new SecretsController.QuotaChanged(result.id(), result.perDay()));
    }

    /** Exposes the credential to one more skill and environment variable. */
    @PostMapping(path = WebRoutes.SECRET_BY_ID + "/bindings",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.BindingView> bind(@PathVariable String id,
            @RequestBody BindingRequest body, Authentication caller) {
        BindCredentialUseCase.Result result = bind
                .bind(id, body.skill(), body.env(), subject(caller))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SecretsController.BindingView(result.skill(), result.env()));
    }

    /** Removes one binding; the value stays. */
    @DeleteMapping(WebRoutes.SECRET_BY_ID + "/bindings")
    ResponseEntity<Void> unbind(@PathVariable String id, @RequestParam String skill,
            @RequestParam String env, Authentication caller) {
        if (!unbind.unbind(id, skill, env, subject(caller))) {
            throw new SkillNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /** Who is receiving this credential, and through which skill and variable. */
    @GetMapping(path = WebRoutes.SECRET_BY_ID + "/shares",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.SharesResponse> shares(@PathVariable String id,
            @RequestParam(required = false) String skill, Authentication caller) {
        return ResponseEntity.ok(sharesResponse(listShares.list(id, skill, subject(caller))));
    }

    /**
     * Hands the credential's use on one skill to an accepted collaborator — the only write of its
     * kind in the system.
     *
     * <p>The page shows the risk in a sentence and asks for confirmation before calling this; the
     * server does not re-ask, because a browser confirmation is not a thing a server can see.
     */
    @PostMapping(path = WebRoutes.SECRET_BY_ID + "/shares",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SecretsController.SharesResponse> share(@PathVariable String id,
            @RequestBody ShareRequest body, Authentication caller) {
        ShareSecretUseCase.Result result = share
                .share(id, body.skill(), body.user(), subject(caller))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SecretsController.SharesResponse(
                result.items().stream()
                        .map(row -> new SecretsController.ShareItem(row.user(), row.skill(),
                                row.env(), row.createdAt()))
                        .toList()));
    }

    /** Withdraws the share; the next lease is already without it. */
    @DeleteMapping(WebRoutes.SECRET_BY_ID + "/shares")
    ResponseEntity<Void> unshare(@PathVariable String id, @RequestParam String skill,
            @RequestParam String user, Authentication caller) {
        if (!unshare.unshare(id, skill, user, subject(caller))) {
            throw new SkillNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private static SecretsController.SharesResponse sharesResponse(
            ListSecretSharesUseCase.Result result) {
        return new SecretsController.SharesResponse(result.items().stream()
                .map(row -> new SecretsController.ShareItem(row.user(), row.skill(), row.env(),
                        row.createdAt()))
                .toList());
    }

    /** A browser session acts as itself, with no scopes and no key id. */
    private static AuthenticatedSubject subject(Authentication caller) {
        return AuthenticatedSubject.ofSession(caller.getName());
    }

    /* ---- wire shapes, shared with the API plane so the two cannot drift ---- */

    record CreateRequest(String name, String value, String skill, String env) {
    }

    record ValueRequest(String value) {
    }

    record QuotaRequest(int perDay) {
    }

    record BindingRequest(String skill, String env) {
    }

    record ShareRequest(String skill, String user) {
    }
}

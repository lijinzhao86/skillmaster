package com.skillmasterai.api;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.usecase.MyTagDefinitionUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The private-tag definitions on the API plane (v14).
 *
 * <p><strong>No delete, and that is deliberate.</strong> Removing a definition takes every
 * attachment with it, including the ones the caller cannot currently see, and cannot be undone — the
 * high-risk class ADR 0079 keeps on the browser plane behind its confirmation dialog. The CLI's
 * three commands are create, rename and describe; this controller is their API.
 *
 * <p>One request may change the name and the sentence. The order is fixed: rename first, then
 * describe, so the returned row is the final one.
 */
@RestController
@RequestMapping(path = MyTagController.BASE)
class MyTagController {

    static final String BASE = "/api/v1/me/tags";

    private final MyTagDefinitionUseCase definitions;

    MyTagController(MyTagDefinitionUseCase definitions) {
        this.definitions = definitions;
    }

    /**
     * Every definition, its sentence and its effective count; ≤100, so one response.
     *
     * <p>{@code name} resolves one definition by normalized word (the CLI's rename / describe);
     * it is the server that decides two spellings are one word. When present, {@code q} is ignored.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.MyTagsList> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(
                OrganizationResponses.MyTagsList.of(definitions.list(q, name, subject)));
    }

    /** Creates a definition; it is not attached to any skill. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Definition> create(@RequestBody CreateBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SkillTagService.Definition created =
                definitions.create(body.name(), body.description(), subject);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OrganizationResponses.Definition.of(created));
    }

    /**
     * Renames and/or describes one definition.
     *
     * <p>{@code description} present-but-empty clears the sentence; absent leaves it alone. That is
     * the difference the CLI's {@code --text ""} and the console's "empty the field" both mean
     * (TD Q6).
     */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Definition> update(@PathVariable String id,
            @RequestBody UpdateBody body, @AuthenticationPrincipal AuthenticatedSubject subject) {
        boolean rename = body.name() != null;
        boolean describe = body.description() != null;
        if (!rename && !describe) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a patch needs a name or a description");
        }
        // One use-case call, one transaction: a refused sentence must not leave the rename behind.
        SkillTagService.Definition result = definitions.update(id,
                rename ? body.name() : null, describe ? body.description() : null, subject);
        return ResponseEntity.ok(OrganizationResponses.Definition.of(result));
    }

    record CreateBody(String name, String description) {
    }

    /** Both fields optional; {@code null} means "not this one", not "clear it". */
    record UpdateBody(String name, String description) {
    }
}

package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.usecase.MyTagDefinitionUseCase;
import com.skillmasterai.usecase.WebTagsUseCase;
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
 * The console's tag page: two tabs and the definition writes, including the one delete (v14).
 *
 * <p><strong>The delete lives here and nowhere else.</strong> It takes every attachment, including
 * the ones the caller cannot see, and cannot be undone; ADR 0079 keeps that class of action behind
 * the browser's confirmation dialog. The CLI has create, rename and describe.
 *
 * <p>Two response shapes for the two tabs, and they are not interchangeable: the private tab's row
 * carries a sentence and an id, the public one a key and a different count.
 */
@RestController
@RequestMapping(path = WebTagsController.BASE)
class WebTagsController {

    static final String BASE = "/web/tags";

    private final WebTagsUseCase tags;
    private final MyTagDefinitionUseCase definitions;

    WebTagsController(WebTagsUseCase tags, MyTagDefinitionUseCase definitions) {
        this.tags = tags;
        this.definitions = definitions;
    }

    /**
     * @param tab {@code public} opens the read-only public list; anything else is the private tab.
     *            There is no {@code tab=private} — the default is the private one.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> list(@RequestParam(required = false) String tab,
            @RequestParam(required = false) String q, Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        if ("public".equals(tab)) {
            return ResponseEntity.ok(OrganizationResponses.WebPublicTags.of(
                    tags.publicTags(q, subject)));
        }
        return ResponseEntity.ok(OrganizationResponses.WebMyTags.of(tags.myTags(q, subject)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Definition> create(@RequestBody CreateBody body,
            Authentication caller) {
        SkillTagService.Definition created = definitions.create(body.name(), body.description(),
                AuthenticatedSubject.ofSession(caller.getName()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OrganizationResponses.Definition.of(created));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Definition> update(@PathVariable String id,
            @RequestBody UpdateBody body, Authentication caller) {
        boolean rename = body.name() != null;
        boolean describe = body.description() != null;
        if (!rename && !describe) {
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "a patch needs a name or a description");
        }
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        // One use-case call, one transaction: a refused sentence must not leave the rename behind.
        SkillTagService.Definition result = definitions.update(id,
                rename ? body.name() : null, describe ? body.description() : null, subject);
        return ResponseEntity.ok(OrganizationResponses.Definition.of(result));
    }

    /** The red-dialog action: the definition and every attachment go together. */
    @DeleteMapping("/{id}")
    ResponseEntity<OrganizationResponses.Changed> delete(@PathVariable String id,
            Authentication caller) {
        definitions.delete(id, AuthenticatedSubject.ofSession(caller.getName()));
        return ResponseEntity.ok(new OrganizationResponses.Changed(true));
    }

    record CreateBody(String name, String description) {
    }

    record UpdateBody(String name, String description) {
    }
}

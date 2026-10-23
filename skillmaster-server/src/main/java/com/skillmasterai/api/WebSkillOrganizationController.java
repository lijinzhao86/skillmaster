package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.JoinSkillLibraryUseCase;
import com.skillmasterai.usecase.LeaveSkillLibraryUseCase;
import com.skillmasterai.usecase.ReadSkillTagsUseCase;
import com.skillmasterai.usecase.WriteMyTagUseCase;
import com.skillmasterai.usecase.WritePublicTagUseCase;
import com.skillmasterai.usecase.WriteSkillCategoryUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The information page's organization writes, on the browser plane (v14).
 *
 * <p><strong>The same use cases as the API controller beside it.</strong> The only differences are
 * the credential (a session, with CSRF enforced by the filter chain) and the caller conversion.
 * Every refusal — 403 for a visible skill that is not in the library, 409 for a dormant entry, 404
 * for a tag that is not attached — is produced by the use case in both places (TD Q8).
 */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebSkillOrganizationController {

    private final ReadSkillTagsUseCase readTags;
    private final JoinSkillLibraryUseCase joinLibrary;
    private final LeaveSkillLibraryUseCase leaveLibrary;
    private final WriteSkillCategoryUseCase writeCategory;
    private final WritePublicTagUseCase writePublicTag;
    private final WriteMyTagUseCase writeMyTag;

    WebSkillOrganizationController(ReadSkillTagsUseCase readTags,
            JoinSkillLibraryUseCase joinLibrary, LeaveSkillLibraryUseCase leaveLibrary,
            WriteSkillCategoryUseCase writeCategory, WritePublicTagUseCase writePublicTag,
            WriteMyTagUseCase writeMyTag) {
        this.readTags = readTags;
        this.joinLibrary = joinLibrary;
        this.leaveLibrary = leaveLibrary;
        this.writeCategory = writeCategory;
        this.writePublicTag = writePublicTag;
        this.writeMyTag = writeMyTag;
    }

    @GetMapping(path = WebSkillRoutes.TAGS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.SkillTags> tags(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return ResponseEntity.ok(OrganizationResponses.SkillTags.of(readTags
                .read(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
    }

    @PostMapping(path = WebSkillRoutes.LIBRARY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> join(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return changed(joinLibrary.join(namespace, name,
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @DeleteMapping(path = WebSkillRoutes.LIBRARY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> leave(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return changed(leaveLibrary.leave(namespace, name,
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @PutMapping(path = WebSkillRoutes.CATEGORY, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> setCategory(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CategoryBody body, Authentication caller) {
        return changed(writeCategory.set(namespace, name, body.key(),
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @DeleteMapping(path = WebSkillRoutes.CATEGORY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> clearCategory(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return changed(writeCategory.clear(namespace, name,
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @PostMapping(path = WebSkillRoutes.PUBLIC_TAGS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> addPublicTag(@PathVariable String namespace,
            @PathVariable String name, @RequestBody TagBody body, Authentication caller) {
        return changed(writePublicTag.attach(namespace, name, body.name(),
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @DeleteMapping(WebSkillRoutes.PUBLIC_TAGS)
    ResponseEntity<OrganizationResponses.Changed> removePublicTag(@PathVariable String namespace,
            @PathVariable String name, @RequestParam("name") String tagName, Authentication caller) {
        if (!writePublicTag.detach(namespace, name, tagName,
                AuthenticatedSubject.ofSession(caller.getName()))) {
            throw new SkillNotFoundException();
        }
        return changed(java.util.Optional.of(true));
    }

    @PostMapping(path = WebSkillRoutes.MY_TAGS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> addMyTag(@PathVariable String namespace,
            @PathVariable String name, @RequestBody TagBody body, Authentication caller) {
        return changed(writeMyTag.attach(namespace, name, body.name(),
                AuthenticatedSubject.ofSession(caller.getName())));
    }

    @DeleteMapping(WebSkillRoutes.MY_TAGS)
    ResponseEntity<OrganizationResponses.Changed> removeMyTag(@PathVariable String namespace,
            @PathVariable String name, @RequestParam("name") String tagName, Authentication caller) {
        if (!writeMyTag.detach(namespace, name, tagName,
                AuthenticatedSubject.ofSession(caller.getName()))) {
            throw new SkillNotFoundException();
        }
        return changed(java.util.Optional.of(true));
    }

    private static ResponseEntity<OrganizationResponses.Changed> changed(
            java.util.Optional<Boolean> changed) {
        return ResponseEntity.ok(new OrganizationResponses.Changed(
                changed.orElseThrow(SkillNotFoundException::new)));
    }

    record TagBody(String name) {
    }

    record CategoryBody(String key) {
    }
}

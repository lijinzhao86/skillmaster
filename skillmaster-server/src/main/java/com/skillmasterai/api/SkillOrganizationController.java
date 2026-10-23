package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.JoinSkillLibraryUseCase;
import com.skillmasterai.usecase.LeaveSkillLibraryUseCase;
import com.skillmasterai.usecase.ReadSkillTagsUseCase;
import com.skillmasterai.usecase.RestoreSkillUseCase;
import com.skillmasterai.usecase.WriteMyTagUseCase;
import com.skillmasterai.usecase.WritePublicTagUseCase;
import com.skillmasterai.usecase.WriteSkillCategoryUseCase;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * How a skill is organized, on the API plane (v14).
 *
 * <p><strong>Every action here is one use case, and both planes call the same one.</strong> The
 * browser controller beside this one differs only in how it learns the caller (a session rather
 * than a token) and in carrying a CSRF token; the decisions, the refusals and the audit rows come
 * from the same place (TD Q8).
 *
 * <p><strong>The six writes that can be a no-op answer 200 {@code {"changed": …}}.</strong> A
 * repeat join, a repeated tag, the shelf a skill already sits on and clearing nothing are all
 * states the caller asked for; 204 would leave the client unable to tell them from a real change.
 * A removal whose subject is absent is a 404 or a 409, not a no-op.
 */
@RestController
@RequestMapping(path = SkillRoutes.BASE)
class SkillOrganizationController {

    private final ReadSkillTagsUseCase readTags;
    private final JoinSkillLibraryUseCase joinLibrary;
    private final LeaveSkillLibraryUseCase leaveLibrary;
    private final WriteSkillCategoryUseCase writeCategory;
    private final WritePublicTagUseCase writePublicTag;
    private final WriteMyTagUseCase writeMyTag;
    private final RestoreSkillUseCase restore;

    SkillOrganizationController(ReadSkillTagsUseCase readTags,
            JoinSkillLibraryUseCase joinLibrary, LeaveSkillLibraryUseCase leaveLibrary,
            WriteSkillCategoryUseCase writeCategory, WritePublicTagUseCase writePublicTag,
            WriteMyTagUseCase writeMyTag, RestoreSkillUseCase restore) {
        this.readTags = readTags;
        this.joinLibrary = joinLibrary;
        this.leaveLibrary = leaveLibrary;
        this.writeCategory = writeCategory;
        this.writePublicTag = writePublicTag;
        this.writeMyTag = writeMyTag;
        this.restore = restore;
    }

    /** The three groups: category, public tags, the caller's private tags. */
    @GetMapping(path = SkillRoutes.TAGS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.SkillTags> tags(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(OrganizationResponses.SkillTags.of(readTags
                .read(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new)));
    }

    /** Adds the reference. A repeat is {@code changed=false}, including a dormant reference. */
    @PostMapping(path = SkillRoutes.LIBRARY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> join(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(joinLibrary.join(namespace, name, subject));
    }

    /** Removes the reference; a skill with none is a 409 from the use case. */
    @DeleteMapping(path = SkillRoutes.LIBRARY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> leave(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(leaveLibrary.leave(namespace, name, subject));
    }

    /** Replaces the skill's shelf. Same word is {@code changed=false}. */
    @PutMapping(path = SkillRoutes.CATEGORY, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> setCategory(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CategoryBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(writeCategory.set(namespace, name, body.key(), subject));
    }

    /** Clears the shelf; no shelf is {@code changed=false}. */
    @DeleteMapping(path = SkillRoutes.CATEGORY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> clearCategory(@PathVariable String namespace,
            @PathVariable String name, @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(writeCategory.clear(namespace, name, subject));
    }

    /** The author's word. A repeat on the normalized key is {@code changed=false}. */
    @PostMapping(path = SkillRoutes.PUBLIC_TAGS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> addPublicTag(@PathVariable String namespace,
            @PathVariable String name, @RequestBody TagBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(writePublicTag.attach(namespace, name, body.name(), subject));
    }

    /** Removes the author's word; one that is not on the skill is a 404 {@code TAG_NOT_ATTACHED}. */
    @DeleteMapping(SkillRoutes.PUBLIC_TAGS)
    ResponseEntity<OrganizationResponses.Changed> removePublicTag(@PathVariable String namespace,
            @PathVariable String name, @RequestParam("name") String tagName,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        if (!writePublicTag.detach(namespace, name, tagName, subject)) {
            throw new SkillNotFoundException();
        }
        // Detaching has no no-op: a word that was not there is a 404, not changed=false. It is
        // still answered in the same shape so a caller has one response to read.
        return changed(Optional.of(true));
    }

    /** The caller's private word; a name with no definition creates one. */
    @PostMapping(path = SkillRoutes.MY_TAGS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Changed> addMyTag(@PathVariable String namespace,
            @PathVariable String name, @RequestBody TagBody body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        return changed(writeMyTag.attach(namespace, name, body.name(), subject));
    }

    /** Detaches the caller's word; the definition and its sentence stay. */
    @DeleteMapping(SkillRoutes.MY_TAGS)
    ResponseEntity<OrganizationResponses.Changed> removeMyTag(@PathVariable String namespace,
            @PathVariable String name, @RequestParam("name") String tagName,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        if (!writeMyTag.detach(namespace, name, tagName, subject)) {
            throw new SkillNotFoundException();
        }
        return changed(Optional.of(true));
    }

    /**
     * Clears a soft delete. The owner's alone; a live skill is a 409 {@code skill_not_deleted},
     * and an address the caller cannot touch is §4.1's 404.
     */
    @PostMapping(SkillRoutes.RESTORE)
    ResponseEntity<Void> restore(@PathVariable String namespace, @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        restore.restoreFromApi(namespace, name, subject).orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<OrganizationResponses.Changed> changed(
            Optional<Boolean> changed) {
        return ResponseEntity.ok(new OrganizationResponses.Changed(
                changed.orElseThrow(SkillNotFoundException::new)));
    }

    record TagBody(String name) {
    }

    record CategoryBody(String key) {
    }
}

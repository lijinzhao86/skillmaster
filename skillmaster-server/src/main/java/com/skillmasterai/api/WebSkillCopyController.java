package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.CopySkillUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.model.CopiedSkill;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser plane's copy: somebody else's published version becomes the caller's own skill (v54,
 * ADR 0173).
 *
 * <p><strong>Its own controller</strong> rather than a method on the lifecycle one: a copy reads
 * one skill and writes another, and the lifecycle controller is where actions on <em>this</em>
 * skill live. Keeping them apart is also what keeps either file small.
 *
 * <p>Thin, like its neighbours: the rules are in {@link CopySkillUseCase}; here it is a body, a
 * pin, a status. The draft id is generated inside the transaction, so the whole action goes through
 * {@link DraftRecorder} — a short-code collision aborts the transaction and the retry has to begin
 * from the top, exactly as {@code skill create} does.
 */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebSkillCopyController {

    private final CopySkillUseCase copySkill;
    private final DraftRecorder drafts;

    WebSkillCopyController(CopySkillUseCase copySkill, DraftRecorder drafts) {
        this.copySkill = copySkill;
        this.drafts = drafts;
    }

    @PostMapping(WebSkillRoutes.COPY)
    ResponseEntity<CopiedSkillResponse> copy(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CopySkillBody body,
            Authentication caller) {
        VersionPin pin = pinOf(body.version());
        CopiedSkill copied = drafts.record(() -> copySkill
                .copy(namespace, name, body.name(), pin,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new));
        return ResponseEntity.status(HttpStatus.CREATED).body(CopiedSkillResponse.of(copied));
    }

    /** Absent means the live version; anything else must be a version name or {@code sha256:…}. */
    private static VersionPin pinOf(String version) {
        if (version == null || version.isBlank()) {
            return new VersionPin.Latest();
        }
        return SkillAddress.pinOf(version, false).orElseThrow(SkillNotFoundException::new);
    }
}

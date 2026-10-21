package com.skillmasterai.api;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.usecase.CreateSkillUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import com.skillmasterai.usecase.SubmitSkillVersionUseCase;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The browser's two ways of bringing content in, plus the one that creates a skill outright.
 *
 * <p><strong>No third submission pipeline.</strong> An import is the existing submit use case with
 * a multipart adapter in front of it — the same validation, the same idempotence, the same audit.
 * What this controller adds is the plane's authentication (session + CSRF), the request shape
 * ({@code file}, {@code name}, {@code note}), and the retry boundary every submission needs: a
 * version code can collide, and the retry has to start a new transaction outside the use case's
 * own {@code @Transactional} method.
 *
 * <p><strong>The web path requires a name.</strong> The package's metadata may carry one, but the
 * page does not parse archives: what the person typed is the only source it has, so a blank name is
 * refused here rather than silently filled from a file the page never read. The note is different —
 * left empty it may still come from the package, which is what "说明留空 = 没给" means.
 */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebImportController {

    private final SubmitSkillUseCase submitSkill;
    private final SubmitSkillVersionUseCase submitVersion;
    private final CreateSkillUseCase createSkill;
    private final DraftRecorder drafts;

    WebImportController(SubmitSkillUseCase submitSkill, SubmitSkillVersionUseCase submitVersion,
            CreateSkillUseCase createSkill, DraftRecorder drafts) {
        this.submitSkill = submitSkill;
        this.submitVersion = submitVersion;
        this.createSkill = createSkill;
        this.drafts = drafts;
    }

    /** Adds a version to a skill that already exists. */
    @PostMapping(path = WebSkillRoutes.IMPORT, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubmitResponse> importVersion(@PathVariable String namespace,
            @PathVariable String name, @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String draftName,
            @RequestPart(value = "note", required = false) String note, Authentication caller) {
        String required = requireName(draftName);
        SubmittedSkill submitted = drafts.record(() -> submitVersion.submitVersion(namespace, name,
                        bytes(file), required, note, AuthenticatedSubject.ofSession(caller.getName())))
                .orElseThrow(SkillNotFoundException::new);
        return response(submitted);
    }

    /** Brings a package into the caller's own namespace; a name nobody has creates the skill. */
    @PostMapping(path = WebSkillRoutes.IMPORT_INTO_NAMESPACE,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubmitResponse> importSkill(@RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String draftName,
            @RequestPart(value = "note", required = false) String note, Authentication caller) {
        String required = requireName(draftName);
        SubmittedSkill submitted = drafts.record(() -> submitSkill.submit(bytes(file), required,
                note, AuthenticatedSubject.ofSession(caller.getName())));
        return response(submitted);
    }

    /**
     * Creates a skill and its first draft in one action (ADR 0070).
     *
     * <p>A body-carried request rather than multipart: there is no file, and the three fields are
     * the whole of it.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubmitResponse> createSkill(@RequestBody CreateBody body, Authentication caller) {
        if (body == null) {
            throw new RequestException(ErrorCode.INVALID_REQUEST, "a body is required",
                    java.util.List.of(new com.skillmasterai.common.ApiError.Detail("body",
                            "required")));
        }
        SubmittedSkill submitted = drafts.record(() -> createSkill.create(body.name(),
                body.description(), body.versionName(),
                AuthenticatedSubject.ofSession(caller.getName())));
        return response(submitted);
    }

    private static ResponseEntity<SubmitResponse> response(SubmittedSkill submitted) {
        return ResponseEntity
                .status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SubmitResponse.of(submitted));
    }

    /** The page never reads the archive, so a blank field is a missing one — not a fallback. */
    private static String requireName(String draftName) {
        if (draftName == null || draftName.isBlank()) {
            throw new RequestException(ErrorCode.DRAFT_NAME_REQUIRED,
                    "an imported version needs a name; type one in the dialog");
        }
        return draftName;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IngestException("the uploaded file could not be read", "file", "unreadable");
        }
    }

    record CreateBody(String name, String description, String versionName) {
    }
}

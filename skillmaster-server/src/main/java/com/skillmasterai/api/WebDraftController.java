package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.CreateDraftFromVersionUseCase;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.WebEditDraftFileUseCase;
import com.skillmasterai.usecase.model.CreateDraftCommand;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.usecase.model.DraftEditResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser plane's two draft-writing routes (v8 TD §接口).
 *
 * <p>One controller for both because they are one subject: a new draft born from an edit, and an
 * edit to a draft that already exists. The identity is the session, exactly as
 * {@link WebSkillController} reads it; the CSRF and session gates live in {@code SecurityConfig}'s
 * {@code /web/**} chain and are not repeated here.
 */
@RestController
@RequestMapping(WebSkillRoutes.BASE)
class WebDraftController {

    private final CreateDraftFromVersionUseCase createDraft;
    private final WebEditDraftFileUseCase editDraft;
    private final DraftRecorder drafts;
    private final AccountDirectory accounts;

    WebDraftController(CreateDraftFromVersionUseCase createDraft,
            WebEditDraftFileUseCase editDraft, DraftRecorder drafts, AccountDirectory accounts) {
        this.createDraft = createDraft;
        this.editDraft = editDraft;
        this.drafts = drafts;
        this.accounts = accounts;
    }

    /** Creates a draft from a base version plus its first edit. */
    @PostMapping(WebSkillRoutes.VERSIONS)
    ResponseEntity<DraftResponse> create(@PathVariable String namespace,
            @PathVariable String name, @RequestBody CreateDraftRequest body,
            Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        VersionPin from = body.from() == null || body.from().isBlank()
                ? null
                : SkillAddress.pinOf(body.from(), true)
                        // A `from` that is not a version is a body that cannot be understood, not an
                        // address that names nothing — 400 says which half to fix (v8 TD §接口).
                        .orElseThrow(() -> new com.skillmasterai.common.RequestException(
                                com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                                "from must name a published version or a draft",
                                List.of(new com.skillmasterai.common.ApiError.Detail("from",
                                        "invalid_format"))));
        DraftEdit edit = editOf(body.edit());
        DraftEditResult result = drafts.record(() -> createDraft.create(namespace, name,
                        new CreateDraftCommand(body.name(), body.note(), from, body.fromDigest(),
                                edit), subject))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(response(result));
    }

    /** Applies one edit to an existing draft. */
    @PostMapping(WebSkillRoutes.VERSION_EDITS)
    ResponseEntity<DraftResponse> edit(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String version, @RequestBody EditRequest body, Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        DraftEditResult result = editDraft.edit(namespace, name, version, body.baseDigest(),
                        editOf(body), subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(response(result));
    }

    private DraftResponse response(DraftEditResult result) {
        String submitter = result.version().submittedBy();
        // `Map.of()` is immutable and refuses a null key, which `version()` asks it for when the
        // submitter is absent. A mutable map answers null like every other lookup.
        Map<String, String> handles = new java.util.HashMap<>();
        if (submitter != null) {
            handles.putAll(accounts.handlesOf(List.of(submitter)));
        }
        return new DraftResponse(
                AuthoredSkillResponse.version(result.version(), handles),
                result.manifest().entries().stream()
                        .map(entry -> new AuthoredSkillResponse.File(entry.relpath(),
                                "sha256:" + entry.blobSha256(), entry.size(), entry.isBinary()))
                        .toList());
    }

    private static DraftEdit editOf(EditBody edit) {
        if (edit == null) {
            return null;
        }
        return new DraftEdit(edit.op(), edit.path(), edit.text(), edit.from(), edit.to());
    }

    private static DraftEdit editOf(EditRequest body) {
        return new DraftEdit(body.op(), body.path(), body.text(), body.from(), body.to());
    }

    /** A create request: the labels, the base the page read, and the first edit. */
    record CreateDraftRequest(String name, String note, String from, String fromDigest,
            EditBody edit) {
    }

    /** The three edit shapes, one body because the operation is one thing with one set of fields. */
    record EditBody(String op, String path, String text, String from, String to) {
    }

    /** An edit request: the baseline plus the edit itself. */
    record EditRequest(String baseDigest, String op, String path, String text, String from,
            String to) {
    }

    /** The row as it now reads, and the manifest the page redraws the tree from. */
    record DraftResponse(AuthoredSkillResponse.Version version,
            List<AuthoredSkillResponse.File> files) {
    }
}

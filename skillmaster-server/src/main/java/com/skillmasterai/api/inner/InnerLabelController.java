package com.skillmasterai.api.inner;

import com.skillmasterai.modules.skill.UnknownLabelException;
import com.skillmasterai.usecase.label.InnerLabelUseCase;
import com.skillmasterai.usecase.label.NotOfficialNamespaceException;
import com.skillmasterai.usecase.label.UnreadableSkillMdException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The label plane for the operations service ({@code /inner/v1}, ADR 0198, v72).
 *
 * <p><strong>Three calls, one audience.</strong> List what is public, read the current version's
 * root file, replace the runtime set. There is no route here for a person: the inner connector
 * refuses anything else, and the token is the only credential.
 */
@RestController
@RequestMapping("/inner/v1")
public class InnerLabelController {

    private static final int MAX_PAGE = 500;

    private final InnerLabelUseCase labels;

    public InnerLabelController(InnerLabelUseCase labels) {
        this.labels = labels;
    }

    @GetMapping(path = "/skills", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> skills(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_PAGE));
        InnerLabelUseCase.Page page = labels.publicSkills(cursor, bounded);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items());
        body.put("next_cursor", page.nextCursor());
        return ResponseEntity.ok(body);
    }

    @GetMapping(path = "/skills/{namespace}/{name}/content",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> content(@PathVariable String namespace, @PathVariable String name) {
        try {
            return labels.content(namespace, name)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_skill",
                            "no published skill at " + namespace + "/" + name));
        } catch (NotOfficialNamespaceException notOfficial) {
            return error(HttpStatus.FORBIDDEN, "not_official", notOfficial.getMessage());
        } catch (UnreadableSkillMdException unreadable) {
            return error(HttpStatus.CONFLICT, "skill_md_unreadable", unreadable.getMessage());
        }
    }

    @PutMapping(path = "/skills/{namespace}/{name}/labels",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> replace(@PathVariable String namespace, @PathVariable String name,
            @RequestBody(required = false) LabelsRequest body) {
        List<String> definitionIds = body == null || body.claims() == null ? List.of()
                : body.claims().stream().map(Claim::definitionId).filter(Objects::nonNull).toList();
        String versionId = body == null ? null : body.versionId();
        try {
            return labels.replaceLabels(namespace, name, versionId, definitionIds)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_skill",
                            "no skill at " + namespace + "/" + name));
        } catch (UnknownLabelException unknown) {
            return error(HttpStatus.BAD_REQUEST, "unknown_definition", unknown.getMessage());
        } catch (NotOfficialNamespaceException notOfficial) {
            return error(HttpStatus.FORBIDDEN, "not_official", notOfficial.getMessage());
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message",
                        message == null ? "" : message)));
    }

    /**
     * The whole set, and the version it was computed from.
     *
     * <p>Absent claims read as the empty set, which removes every label. {@code versionId} is what
     * lets the server refuse a draft's labels while another version is live.
     */
    public record LabelsRequest(String versionId, List<Claim> claims) {
    }

    public record Claim(String definitionId) {
    }
}

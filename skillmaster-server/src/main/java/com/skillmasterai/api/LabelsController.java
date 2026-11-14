package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmasterai.modules.skill.LabelDefinition;
import com.skillmasterai.modules.skill.LabelService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public label vocabulary (v65).
 *
 * <p>A read-only list, deliberately without a public total: the size of the public corpus is an
 * internal signal, and the console does not draw counts either. The wire key is the definition id
 * ({@code runtime:python}), so a client can pass it straight back as {@code ?label=}.
 */
@RestController
@RequestMapping(path = "/api/v1")
class LabelsController {

    private final LabelService labels;

    LabelsController(LabelService labels) {
        this.labels = labels;
    }

    @GetMapping(path = "/labels", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<LabelRow>> labels() {
        return ResponseEntity.ok(labels.vocabulary().stream().map(LabelRow::of).toList());
    }

    /** One vocabulary row. {@code short} and {@code full} are the wire names the CLI/console read. */
    record LabelRow(String key, String dimensionKey, String dimensionName, String value,
            @JsonProperty("short") String shortName,
            @JsonProperty("full") String fullName,
            String group, boolean filterable, String state, int sortOrder) {
        static LabelRow of(LabelDefinition definition) {
            return new LabelRow(definition.id(), definition.dimensionKey(),
                    definition.dimensionName(), definition.value(), definition.shortName(),
                    definition.fullName(), definition.group(), definition.filterable(),
                    definition.state(), definition.sortOrder());
        }
    }
}

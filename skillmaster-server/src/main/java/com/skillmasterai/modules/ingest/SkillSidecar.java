package com.skillmasterai.modules.ingest;

import com.skillmasterai.common.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The version metadata that rides beside the content in an exported archive (ADR 0069).
 *
 * <p><strong>It is not a file of the skill.</strong> It carries the two author-plane facts that do
 * not belong to content identity — the version's name and its note — so a download can be
 * submitted back without the author retyping them. It never enters a manifest, never changes a
 * digest, and never counts against the file or byte ceilings.
 *
 * <p><strong>The shape is deliberately tiny.</strong> {@code schema_version} (integer {@code 1})
 * plus a {@code version} object with {@code name} / {@code note}, both strings. Length and
 * whitespace rules are not checked here: {@code VersionLabels} is the system's one definition of
 * those and applying a second one would let the two disagree.
 *
 * <p><strong>Shaped means "a JSON object with a top-level {@code schema_version} key".</strong>
 * That single test is what separates metadata from content at the reserved path: a file with the
 * key is a sidecar attempt and gets a precise {@code metadata_*} reason when it is malformed; a
 * file without it is content sitting on a path the platform reserves, which is refused with
 * {@code reserved_path}.
 */
public record SkillSidecar(String name, String note) {

    /** The reader's internal allowance for this path — not a product limit and not a user-visible error. */
    public static final int MAX_BYTES = 64 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Whether the bytes at the reserved path claim to be metadata.
     *
     * <p>Never throws: a file that is not JSON, or is JSON of another shape, is simply not a
     * sidecar and falls back to the content rule.
     */
    public static boolean looksLikeMetadata(byte[] bytes) {
        try {
            JsonNode root = JSON.readTree(bytes);
            return root != null && root.isObject() && root.get("schema_version") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Reads the sidecar, or refuses it with the reason that names what is wrong.
     *
     * @throws IngestException {@code invalid_upload} with one of the {@code metadata_*} issues
     */
    public static SkillSidecar parse(byte[] bytes) {
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (RuntimeException e) {
            throw invalid("schema_version", "metadata_schema_unsupported",
                    "the version metadata is not readable JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("schema_version", "metadata_schema_unsupported",
                    "the version metadata is not a JSON object");
        }

        List<ApiError.Detail> unknown = new ArrayList<>();
        for (String field : root.propertyNames()) {
            if (!field.equals("schema_version") && !field.equals("version")) {
                unknown.add(new ApiError.Detail("metadata_field", field));
            }
        }
        if (!unknown.isEmpty()) {
            unknown.add(0, new ApiError.Detail(ReservedPaths.SIDECAR, "metadata_unknown_field"));
            throw new IngestException("the version metadata has fields this version does not know",
                    unknown);
        }

        JsonNode schema = root.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || schema.asLong() != 1) {
            throw invalid("schema_version", "metadata_schema_unsupported",
                    "the version metadata's schema_version is not one this server knows");
        }

        JsonNode version = root.get("version");
        if (version == null) {
            return new SkillSidecar(null, null);
        }
        if (!version.isObject()) {
            throw fieldIssue("metadata_field_invalid",
                    "the version metadata's 'version' must be an object", "version");
        }
        List<ApiError.Detail> versionUnknown = new ArrayList<>();
        for (String field : version.propertyNames()) {
            if (!field.equals("name") && !field.equals("note")) {
                versionUnknown.add(new ApiError.Detail("metadata_field", field));
            }
        }
        if (!versionUnknown.isEmpty()) {
            versionUnknown.add(0,
                    new ApiError.Detail(ReservedPaths.SIDECAR, "metadata_unknown_field"));
            throw new IngestException("the version metadata has fields this version does not know",
                    versionUnknown);
        }
        String name = text(version, "name");
        String note = text(version, "note");
        return new SkillSidecar(name, note);
    }

    /**
     * Writes the metadata for a version.
     *
     * <p>Keys with no value are omitted rather than written as {@code null}: a version submitted
     * before v6 has no name, and the schema says a present name is a string. Omitting it is what
     * keeps the file valid for those rows — and it is why such a row's round trip still needs an
     * explicit {@code --name}.
     */
    public static byte[] render(String name, String note) {
        StringBuilder json = new StringBuilder("{\"schema_version\":1");
        boolean hasName = name != null && !name.isBlank();
        boolean hasNote = note != null && !note.isBlank();
        if (hasName || hasNote) {
            json.append(",\"version\":{");
            if (hasName) {
                json.append("\"name\":").append(quote(name));
                if (hasNote) {
                    json.append(',');
                }
            }
            if (hasNote) {
                json.append("\"note\":").append(quote(note));
            }
            json.append('}');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(JsonNode version, String field) {
        JsonNode node = version.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw fieldIssue("metadata_field_invalid",
                    "the version metadata's '" + field + "' must be text", field);
        }
        return node.asString();
    }

    /**
     * A refusal about one field of the metadata, in the wire shape the page reads (TD §接口 6): the
     * issue code says what is wrong, and a second detail names the field, because the designed
     * sentence is 「.skillmaster/metadata.json 的 &lt;字段&gt; 类型不对。」 — it needs both halves.
     */
    private static IngestException fieldIssue(String issue, String message, String field) {
        return new IngestException(message, List.of(
                new ApiError.Detail(ReservedPaths.SIDECAR, issue),
                new ApiError.Detail("metadata_field", field)));
    }

    private static IngestException invalid(String field, String issue, String message) {
        return new IngestException(message, field, issue);
    }

    /**
     * A JSON string literal.
     *
     * <p>Hand-rolled rather than routed through the mapper so the document has a fixed key order
     * and no optional whitespace: two runs over the same facts produce the same bytes, which is
     * what makes the file diffable and the tests exact.
     */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}

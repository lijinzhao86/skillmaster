package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The sidecar's two jobs: say whether the bytes at the reserved path claim to be metadata, and
 * either read them or name what is wrong with them.
 *
 * <p>The discriminator is the one bit everything else hangs on (ADR 0069): a JSON object with a
 * top-level {@code schema_version} is a sidecar attempt — and a malformed one is a precise
 * {@code metadata_*} refusal; anything else is content sitting on a reserved path, which is a
 * different answer. These tests pin that split, the four named refusals, and the renderer's
 * omission of absent fields.
 */
class SkillSidecarTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void onlyAJsonObjectWithSchemaVersionClaimsToBeMetadata() {
        assertThat(SkillSidecar.looksLikeMetadata(bytes("{\"schema_version\":1}"))).isTrue();
        assertThat(SkillSidecar.looksLikeMetadata(bytes("{\"schema_version\":\"1\"}"))).isTrue();
        // Not JSON at all, JSON of another shape, and an object that does not claim the key: all
        // three are content at a reserved path, not a broken sidecar.
        assertThat(SkillSidecar.looksLikeMetadata(bytes("name: pdf-tools"))).isFalse();
        assertThat(SkillSidecar.looksLikeMetadata(bytes("[1,2,3]"))).isFalse();
        assertThat(SkillSidecar.looksLikeMetadata(bytes("{\"version\":{\"name\":\"第一版\"}}")))
                .isFalse();
        assertThat(SkillSidecar.looksLikeMetadata(new byte[0])).isFalse();
    }

    @Test
    void readsTheNameAndTheNote() {
        SkillSidecar sidecar = SkillSidecar.parse(
                bytes("{\"schema_version\":1,\"version\":{\"name\":\"第一版\",\"note\":\"修好了导出\"}}"));

        assertThat(sidecar.name()).isEqualTo("第一版");
        assertThat(sidecar.note()).isEqualTo("修好了导出");
    }

    @Test
    void aMissingVersionObjectIsAnEmptySidecar() {
        // A version submitted before v6 has no name; the schema says a present name is a string, so
        // absence — not null — is how it is written, and this is the file such a row produces.
        SkillSidecar sidecar = SkillSidecar.parse(bytes("{\"schema_version\":1}"));

        assertThat(sidecar.name()).isNull();
        assertThat(sidecar.note()).isNull();
    }

    @Test
    void refusesAnUnknownFieldWithItsIssueAndItsName() {
        // Two details: the issue the page looks up, and the field name its sentence quotes
        // (TD §接口 6 — 「…有无法识别的字段：<字段>。」 needs both halves).
        assertThatThrownBy(() -> SkillSidecar.parse(
                bytes("{\"schema_version\":1,\"tags\":[\"pdf\"]}")))
                .isInstanceOfSatisfying(IngestException.class, error ->
                        assertThat(error.details())
                                .extracting(detail -> detail.field() + "=" + detail.issue())
                                .containsExactly(
                                        ".skillmaster/metadata.json=metadata_unknown_field",
                                        "metadata_field=tags"));
    }

    @Test
    void refusesASchemaThisServerDoesNotKnow() {
        assertThatThrownBy(() -> SkillSidecar.parse(bytes("{\"schema_version\":99}")))
                .isInstanceOfSatisfying(IngestException.class, error ->
                        assertThat(error.details())
                                .extracting(detail -> detail.field() + "=" + detail.issue())
                                .containsExactly("schema_version=metadata_schema_unsupported"));
        // A string where the schema wants an integer is the same answer: neither is a version this
        // server can read.
        assertThatThrownBy(() -> SkillSidecar.parse(bytes("{\"schema_version\":\"1\"}")))
                .isInstanceOfSatisfying(IngestException.class, error ->
                        assertThat(error.details())
                                .extracting(detail -> detail.field() + "=" + detail.issue())
                                .containsExactly("schema_version=metadata_schema_unsupported"));
    }

    @Test
    void refusesAValueOfTheWrongTypeWithItsIssueAndItsName() {
        assertThatThrownBy(() -> SkillSidecar.parse(
                bytes("{\"schema_version\":1,\"version\":{\"name\":7}}")))
                .isInstanceOfSatisfying(IngestException.class, error ->
                        assertThat(error.details())
                                .extracting(detail -> detail.field() + "=" + detail.issue())
                                .containsExactly(
                                        ".skillmaster/metadata.json=metadata_field_invalid",
                                        "metadata_field=name"));
    }

    @Test
    void rendersOnlyTheFieldsItHas() {
        assertThat(new String(SkillSidecar.render("第一版", null), StandardCharsets.UTF_8))
                .isEqualTo("{\"schema_version\":1,\"version\":{\"name\":\"第一版\"}}");
        assertThat(new String(SkillSidecar.render(null, "说明"), StandardCharsets.UTF_8))
                .isEqualTo("{\"schema_version\":1,\"version\":{\"note\":\"说明\"}}");
        // Nothing to say is still a valid sidecar, and says it by leaving the object out.
        assertThat(new String(SkillSidecar.render(null, null), StandardCharsets.UTF_8))
                .isEqualTo("{\"schema_version\":1}");
    }

    @Test
    void roundTripsThroughItsOwnRenderer() {
        byte[] rendered = SkillSidecar.render("带\"引号\"与\n换行", "说明");

        SkillSidecar read = SkillSidecar.parse(rendered);
        assertThat(read.name()).isEqualTo("带\"引号\"与\n换行");
        assertThat(read.note()).isEqualTo("说明");
        // Two runs over the same facts are the same bytes — what makes the file diffable.
        assertThat(SkillSidecar.render("带\"引号\"与\n换行", "说明")).isEqualTo(rendered);
    }
}

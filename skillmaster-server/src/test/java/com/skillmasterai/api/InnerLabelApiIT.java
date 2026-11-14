package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.skill.UnknownLabelException;
import com.skillmasterai.usecase.label.InnerLabelUseCase;
import com.skillmasterai.usecase.label.NotOfficialNamespaceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

/**
 * v72's inner label surface: what the operations service may read, what it may replace, and what
 * the vocabulary refuses.
 *
 * <p>The connector and token are covered by {@code InnerPlaneFilterTest}; these cases call the use
 * case directly, because the route is an adapter and the rules about official namespaces, current
 * versions and whole-set replacement live below it.
 */
@Sql("/sql/truncate-business-tables.sql")
class InnerLabelApiIT extends AbstractOrganizeIT {

    @Autowired
    InnerLabelUseCase labels;

    @Autowired
    BlobStore blobs;

    @Test
    void contentIsTheCurrentVersionsRootFileAndManifest() {
        String skillId = officialPublicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);
        putSkillMd(versionId, dockerSkill());

        Optional<InnerLabelUseCase.Content> content = labels.content("skillmaster", "pdf-tools");

        assertThat(content).isPresent();
        assertThat(content.get().skillId()).isEqualTo(skillId);
        assertThat(content.get().versionId()).isEqualTo(versionId);
        assertThat(content.get().skillMd()).contains("docker run --rm image");
        assertThat(content.get().files())
                .extracting(InnerLabelUseCase.FileFact::relpath).containsExactly("SKILL.md");
    }

    @Test
    void replacingSetsTheWholeFinalTable() {
        String skillId = officialPublicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);

        InnerLabelUseCase.Replaced replaced = labels.replaceLabels("skillmaster", "pdf-tools",
                versionId, List.of("runtime:docker", "runtime:prompt-only")).orElseThrow();
        assertThat(replaced.applied()).isTrue();
        assertThat(replaced.replaced()).isEqualTo(2);
        assertThat(labelsOf(skillId)).containsExactlyInAnyOrder("runtime:docker",
                "runtime:prompt-only");

        labels.replaceLabels("skillmaster", "pdf-tools", versionId, List.of("runtime:docker"));
        assertThat(labelsOf(skillId)).containsExactly("runtime:docker");

        labels.replaceLabels("skillmaster", "pdf-tools", versionId, List.of());
        assertThat(labelsOf(skillId)).isEmpty();
    }

    @Test
    void aVersionThatIsNotLiveIsDeferredAndLeavesTheTableAlone() {
        String skillId = officialPublicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);

        InnerLabelUseCase.Replaced replaced = labels.replaceLabels("skillmaster", "pdf-tools",
                "a-draft-version", List.of("runtime:docker")).orElseThrow();

        assertThat(replaced.applied()).isFalse();
        assertThat(replaced.replaced()).isZero();
        assertThat(labelsOf(skillId)).isEmpty();
    }

    @Test
    void aValueOutsideTheRuntimeDimensionIsRefused() {
        String skillId = officialPublicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);

        assertThatThrownBy(() -> labels.replaceLabels("skillmaster", "pdf-tools", versionId,
                List.of("pricing_type:free"))).isInstanceOf(UnknownLabelException.class);
        assertThatThrownBy(() -> labels.replaceLabels("skillmaster", "pdf-tools", versionId,
                List.of("runtime:nope"))).isInstanceOf(UnknownLabelException.class);
    }

    @Test
    void aPersonalNamespaceIsRefused() {
        publicSkill("pdf-tools");

        assertThatThrownBy(() -> labels.content("demo", "pdf-tools"))
                .isInstanceOf(NotOfficialNamespaceException.class);
    }

    @Test
    void theListOnlyReturnsDiscoverableSkills() {
        officialPublicSkill("visible");
        officialSkill("hidden-private", "private", "a-version", null, null);
        officialSkill("hidden-offline", "public", "b-version", AT, null);
        officialSkill("hidden-deleted", "public", "c-version", null, AT);
        skill(OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID, "hidden-draft", "private", null, null,
                null, null);

        List<String> names = labels.publicSkills(null, 100).items().stream()
                .map(InnerLabelUseCase.PublicSkill::name).toList();

        assertThat(names).contains("visible");
        assertThat(names).doesNotContain("hidden-private", "hidden-offline", "hidden-deleted",
                "hidden-draft");
    }

    private String officialPublicSkill(String name) {
        return skill(OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID, name, "public",
                com.skillmasterai.common.Ulid.generate(), null, null, null);
    }

    private void officialSkill(String name, String visibility, String versionId, String offlineAt,
            String deletedAt) {
        skill(OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID, name, visibility, versionId, offlineAt,
                deletedAt, null);
    }

    private void putSkillMd(String versionId, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        BlobStore.BlobRef ref = blobs.put(bytes);
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                VALUES (:version, 'SKILL.md', :sha, :size, 0)
                """)
                .param("version", versionId)
                .param("sha", ref.sha256Hex())
                .param("size", bytes.length)
                .update();
    }

    private List<String> labelsOf(String skillId) {
        return jdbc.sql("SELECT definition_id FROM skill_label WHERE skill_id = :id")
                .param("id", skillId)
                .query(String.class)
                .list();
    }

    private String currentVersion(String skillId) {
        return jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
    }

    private void versionRow(String skillId, String versionId, int number) {
        String name = jdbc.sql("SELECT name FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
        jdbc.sql("""
                INSERT INTO skill_version
                  (id, skill_id, number, digest, file_count, total_bytes, submitted_by,
                   submitted_at, state, state_at, title, description, frontmatter, version,
                   first_published_at)
                VALUES (:id, :skill, :number, :digest, 0, 0, :user, :at, 'published', :at,
                        :name, :name, '{}', :versionName, :at)
                ON CONFLICT (id) DO NOTHING
                """).param("id", versionId).param("skill", skillId).param("number", number)
                .param("digest", "digest-" + versionId).param("user", OFFICIAL_USER_ID)
                .param("name", name).param("versionName", "1.0." + number)
                .param("at", AT).update();
    }

    private static String dockerSkill() {
        return "---\nname: pdf-tools\ndescription: d\n---\n```bash\ndocker run --rm image\n```\n";
    }
}

package com.skillmasterai.modules.skill;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.skillmasterai.modules.skill.SourceAdapter.FetchedSource;
import com.skillmasterai.modules.skill.SourceAdapter.LicenseCandidate;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.internal.SourceRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a skill's source from its frontmatter, keeps the snapshot current, and merges the
 * license declaration with what the adapter read.
 */
public final class SourceService {

    private final SourceRepository repository;
    private final SourceRegistry registry;
    private final ObjectMapper json;

    public SourceService(SourceRepository repository, SourceRegistry registry, ObjectMapper json) {
        this.repository = repository;
        this.registry = registry;
        this.json = json;
    }

    /**
     * Writes the origin and a pending snapshot for a newly published version. No network here: the
     * publish transaction must not wait on GitHub. The scheduler (or a test) calls
     * {@link #refresh(String, String)} afterwards.
     */
    public void record(String skillId, String versionId, String frontmatterJson,
            boolean officialNamespace, String namespaceSlug, String name) {
        JsonNode root = parse(frontmatterJson);
        JsonNode declared = sourceNode(root);
        String kind;
        String locator;
        String path;
        if (declared != null) {
            kind = declared.path("kind").asText("");
            locator = declared.path("locator").asText("");
            path = declared.path("path").asText("");
        } else if (officialNamespace) {
            kind = OfficialSourceAdapter.KIND;
            locator = namespaceSlug + "/" + name;
            path = "";
        } else {
            return;
        }
        SourceAdapter adapter = registry.get(kind);
        SourceBinding binding = adapter.parse(locator, path);
        SkillSource source = repository.putOrigin(skillId, binding.kind(), binding.locator(),
                binding.path(), binding.label(), binding.url());
        /* A version is immutable, so a second record for the same version is a republish or a
           rollback, not a new fact. Its snapshot already holds the commit the version came from,
           so it must not be reset to pending. */
        if (repository.findSnapshot(source.id(), versionId).isEmpty()) {
            repository.putSnapshot(source.id(), versionId, null, "[]", SourceSnapshot.PENDING);
        }

        String declaration = licenseDeclaration(root);
        /* The license is skipped only when the row already says this version's answer: either the
           source settled it, or it already holds the same declaration. A placeholder — including
           V36's backfilled `missing` row, which never read the frontmatter — is not an answer, so
           a republish or rollback may still fill it in from the version that is being recorded. */
        Optional<SkillLicense> existing = repository.findLicense(versionId);
        boolean settled = existing.map(SkillLicense::status)
                .filter(status -> status == LicenseStatus.OK || status == LicenseStatus.OTHER)
                .isPresent();
        String heldDeclaration = existing.map(SkillLicense::detail)
                .map(this::declaredOf)
                .orElse(null);
        if (settled || (existing.isPresent()
                && java.util.Objects.equals(heldDeclaration, declaration))) {
            return;
        }

        String detail = declaration == null ? "{}" : detail(Map.of("declared", declaration));
        repository.putLicense(new SkillLicense(versionId,
                declaration == null ? "NOASSERTION" : declaration,
                declaration == null ? "未声明" : declaration,
                null,
                declaration == null ? LicenseStatus.MISSING : LicenseStatus.DECLARED_ONLY,
                null, null, null, detail, now()));
    }

    /**
     * Reads the source once and updates its snapshot and license. Failures are stored, not thrown:
     * a page keeps the identity it already has and says the metrics are unavailable.
     */
    public void refresh(String skillId, String versionId) {
        Optional<SkillSource> origin = repository.findOrigin(skillId);
        if (origin.isEmpty()) {
            return;
        }
        SkillSource source = origin.get();
        SourceAdapter adapter = registry.get(source.kind());
        SourceBinding binding = new SourceBinding(source.kind(), source.locator(), source.path(),
                source.label(), source.url());
        FetchedSource fetched = adapter.fetch(binding, null);
        /* A failure keeps what the last success said: the revision is provenance and must not move
           with upstream HEAD, and the metrics stay for the next successful refresh. `stale` is the
           status when there was something to keep; `error` means there never was. */
        Optional<SourceSnapshot> previous = repository.findSnapshot(source.id(), versionId);
        String status = fetched.status();
        if (SourceSnapshot.ERROR.equals(status) && previous.isPresent()
                && (SourceSnapshot.OK.equals(previous.get().status())
                        || SourceSnapshot.STALE.equals(previous.get().status()))) {
            status = SourceSnapshot.STALE;
        }
        repository.putSnapshot(source.id(), versionId, fetched.revision(), fetched.signalsJson(),
                status);
        mergeLicense(versionId, fetched.license());
    }

    private void mergeLicense(String versionId, LicenseCandidate candidate) {
        Optional<SkillLicense> current = repository.findLicense(versionId);
        /* Once the source answered, the version's license is settled. The adapter reads the
           current HEAD, not the commit this version came from, so re-reading it on every daily
           refresh would let upstream's later relicensing rewrite what an old version says. A
           placeholder (declared-only or missing) is not settled: that is the first resolution,
           retried until the source answers. */
        boolean settled = current.map(SkillLicense::status)
                .filter(status -> status == LicenseStatus.OK || status == LicenseStatus.OTHER)
                .isPresent();
        if (settled) {
            return;
        }
        String declared = current.map(SkillLicense::detail)
                .map(this::declaredOf)
                .orElse(null);
        /* An adapter says "nothing to merge" either by returning no candidate or by following the
           `LicenseCandidate` contract: status `missing`, or no SPDX at all. All three are the same
           fact, so they share the placeholder path instead of becoming `other`. */
        if (candidate == null || candidate.spdx() == null || candidate.spdx().isBlank()
                || "missing".equals(candidate.status())) {
            if (current.isEmpty()) {
                repository.putLicense(new SkillLicense(versionId, "NOASSERTION", "未声明", null,
                        LicenseStatus.MISSING, null, null, null, "{}", now()));
            }
            return;
        }
        /* v49 does not adjudicate a disagreement between the declaration and the licence file:
           the file at the source answers "what does this version carry", so it wins. Whether a
           declaration that disagrees should block an ingest is v50's validation, not this page. */
        LicenseStatus status;
        String detail = declared == null ? "{}" : detail(Map.of("declared", declared));
        if (candidate.spdx().startsWith("LicenseRef")
                || candidate.spdx().equals("NOASSERTION")) {
            status = LicenseStatus.OTHER;
        } else {
            status = LicenseStatus.OK;
        }
        repository.putLicense(new SkillLicense(versionId, candidate.spdx(), candidate.name(),
                candidate.copyright(), status, candidate.text(), candidate.sourcePath(),
                candidate.sourceUrl(), detail, now()));
    }

    /** The commit this version came from, when the adapter recorded one. */
    public Optional<String> revision(String skillId, String versionId) {
        return repository.findOrigin(skillId)
                .flatMap(origin -> repository.findSnapshot(origin.id(), versionId))
                .map(SourceSnapshot::revision);
    }

    public String currentVersionId(String skillId) {
        return repository.currentVersionId(skillId);
    }

    public Optional<SkillSource> origin(String skillId) {
        return repository.findOrigin(skillId);
    }

    public Optional<SourceSnapshot> snapshot(String sourceId, String versionId) {
        return repository.findSnapshot(sourceId, versionId);
    }

    public Optional<SkillLicense> license(String versionId) {
        return repository.findLicense(versionId);
    }

    /** The library relation's count — a fact M12 owns; the use series lives in M15. */
    public int joins(String skillId) {
        return repository.joins(skillId);
    }

    private JsonNode parse(String frontmatterJson) {
        try {
            return json.readTree(frontmatterJson == null || frontmatterJson.isBlank()
                    ? "{}" : frontmatterJson);
        } catch (Exception failure) {
            throw new InvalidSourceException("SKILL.md frontmatter is not valid JSON");
        }
    }

    private static JsonNode sourceNode(JsonNode root) {
        JsonNode nested = root.path("metadata").path("source");
        if (nested.isObject()) {
            return nested;
        }
        JsonNode top = root.path("source");
        return top.isObject() ? top : null;
    }

    private static String licenseDeclaration(JsonNode root) {
        String nested = text(root.path("metadata").path("license"));
        return nested != null ? nested : text(root.path("license"));
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private String declaredOf(String detail) {
        try {
            JsonNode node = json.readTree(detail);
            String declared = node.path("declared").asText("");
            return declared.isBlank() ? null : declared;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String detail(Map<String, String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String now() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

}

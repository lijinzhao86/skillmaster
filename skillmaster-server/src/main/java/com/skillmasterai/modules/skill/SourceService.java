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
        record(skillId, versionId, frontmatterJson, officialNamespace, namespaceSlug, name, false);
    }

    /**
     * @param syncedVersion whether the sync wrote this version's snapshot
     *                      ({@code written_by_sync}; see {@link #hasSyncSnapshot}). Only then does
     *                      its existing external origin outrank the upstream frontmatter's
     *                      declaration — the whitelist entry the sync read from is the authority,
     *                      and replacing it would cascade the snapshots away.
     */
    public void record(String skillId, String versionId, String frontmatterJson,
            boolean officialNamespace, String namespaceSlug, String name, boolean syncedVersion) {
        JsonNode root = parse(frontmatterJson);
        JsonNode declared = sourceNode(root);
        Optional<SkillSource> heldOrigin = repository.findOrigin(skillId);
        boolean holdsExternalOrigin = heldOrigin.isPresent()
                && !OfficialSourceAdapter.KIND.equals(heldOrigin.get().kind());
        /* v50: a synced version's origin is the whitelist entry the sync read it from, and that
           outranks whatever the upstream SKILL.md declares. `putOrigin` replaces an origin and
           cascades its snapshots, so letting a declaration win here would delete the history the
           sync just wrote. The preservation is limited to versions whose snapshot the sync wrote
           (`written_by_sync`); a version the sync did not write — including one the refresh has
           since touched — still records what its own frontmatter says. */
        boolean keepSyncedOrigin = syncedVersion && officialNamespace && holdsExternalOrigin;

        SkillSource source;
        if (keepSyncedOrigin) {
            source = heldOrigin.get();
        } else if (declared != null) {
            String kind = declared.path("kind").asText("");
            String locator = declared.path("locator").asText("");
            String path = declared.path("path").asText("");
            SourceAdapter adapter = registry.get(kind);
            SourceBinding binding = adapter.parse(locator, path);
            source = repository.putOrigin(skillId, binding.kind(), binding.locator(),
                    binding.path(), binding.label(), binding.url());
        } else if (officialNamespace) {
            /* The official fallback is only for an official skill that has no origin at all;
               writing it over a GitHub origin would cascade-delete every snapshot the sync
               recorded. */
            if (holdsExternalOrigin) {
                source = heldOrigin.get();
            } else {
                SourceAdapter adapter = registry.get(OfficialSourceAdapter.KIND);
                SourceBinding binding = adapter.parse(namespaceSlug + "/" + name, "");
                source = repository.putOrigin(skillId, binding.kind(), binding.locator(),
                        binding.path(), binding.label(), binding.url());
            }
        } else {
            return;
        }
        /* A version is immutable, so a second record for the same version is a republish or a
           rollback, not a new fact. Its snapshot already holds the commit the version came from,
           so it must not be reset to pending. */
        if (repository.findSnapshot(source.id(), versionId).isEmpty()) {
            repository.putSnapshot(source.id(), versionId, null, "[]", SourceSnapshot.PENDING,
                    false);
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
                status, false);
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

    /**
     * The origin held by the skill at this address, whoever owns it.
     *
     * <p>v50's address preflight asks this before submitting: the same name may already exist in
     * the official namespace and belong to a different platform.
     */
    public Optional<SkillSource> findByAddress(String namespaceId, String name) {
        return repository.findByAddress(namespaceId, name);
    }

    /**
     * Every snapshot the sync wrote for this origin, including versions the operator discarded or
     * revoked. v50's sync skips a download when the upstream commit equals its newest one; a
     * snapshot written by the publish-time refresh does not count.
     */
    public java.util.List<SyncAnchor> anchors(String namespaceId, String kind, String locator,
            String path) {
        return repository.anchors(namespaceId, kind, locator, path).stream()
                .map(anchor -> new SyncAnchor(anchor.skillId(), anchor.versionId(),
                        anchor.revision()))
                .toList();
    }

    /** The skill, the version the anchor came from, and the revision it recorded. */
    public record SyncAnchor(String skillId, String versionId, String revision) {
    }

    /**
     * Whether the sync wrote this version's source facts.
     *
     * <p>The publish path uses this to decide whether the version's provenance is the sync's to
     * keep. The snapshot row's {@code written_by_sync} mark is the fact; a row's mere existence is
     * not, because the refresh also writes one for a published version. It deliberately does not
     * read {@code skill_version.source}: a replay hands the sync an existing draft, and flipping
     * that row's source would also make it eligible for the sync's old-draft cleanup.
     */
    public boolean hasSyncSnapshot(String versionId) {
        return repository.hasSyncSnapshot(versionId);
    }

    /** Whether the draft-time source facts were written, or an unrelated origin already owns it. */
    public enum SyncRecord {
        RECORDED,
        ORIGIN_CONFLICT
    }

    /**
     * Writes the source facts for a draft the sync just submitted, before the operator publishes.
     *
     * <p>Idempotent on purpose: a retry, or a replay of the same content, must not move an existing
     * revision or overwrite a settled license. An origin already held by another platform is a
     * conflict, never a replacement — replacing it cascades away the old snapshots.
     */
    public SyncRecord recordSynced(String skillId, String versionId, SourceBinding binding,
            String revision, SkillLicense license) {
        Optional<SkillSource> existing = repository.findOrigin(skillId);
        SkillSource source;
        if (existing.isPresent()) {
            SkillSource held = existing.get();
            if (!held.kind().equals(binding.kind())
                    || !held.locator().equals(binding.locator())
                    || !held.path().equals(binding.path())) {
                return SyncRecord.ORIGIN_CONFLICT;
            }
            source = held;
        } else {
            source = repository.putOrigin(skillId, binding.kind(), binding.locator(),
                    binding.path(), binding.label(), binding.url());
        }
        if (repository.findSnapshot(source.id(), versionId).isEmpty()) {
            repository.putSnapshot(source.id(), versionId, revision, "[]",
                    SourceSnapshot.PENDING, true);
        } else {
            /* A replay can land on a version the refresh already answered (same content, published
               by hand). Only the mark is missing then; the row's revision and status are settled
               and must not be reset. */
            repository.markSyncSnapshot(source.id(), versionId);
        }
        boolean settled = repository.findLicense(versionId)
                .map(SkillLicense::status)
                .filter(status -> status == LicenseStatus.OK || status == LicenseStatus.OTHER)
                .isPresent();
        if (!settled) {
            repository.putLicense(license);
        }
        return SyncRecord.RECORDED;
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
        String nested = declaredLicense(root.path("metadata").path("license"));
        return nested != null ? nested : declaredLicense(root.path("license"));
    }

    /**
     * A non-text license value is an unrecognised declaration, not an absent one: turning it into
     * null would make the page say "未声明" for a field the upstream deliberately wrote.
     */
    private static String declaredLicense(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual()
                ? (node.asText().isBlank() ? null : node.asText().trim())
                : "NOASSERTION";
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

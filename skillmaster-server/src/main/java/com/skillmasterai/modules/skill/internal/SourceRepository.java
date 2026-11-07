package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.LicenseStatus;
import com.skillmasterai.modules.skill.SkillLicense;
import com.skillmasterai.modules.skill.SkillSource;
import com.skillmasterai.modules.skill.SourceSnapshot;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes the four v49 tables; all of them belong to M12. */
public final class SourceRepository {

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String currentVersionId(String skillId) {
        return jdbc.sql("SELECT current_version_id FROM skill WHERE id = :skill")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    public Optional<SkillSource> findOrigin(String skillId) {
        return jdbc.sql("SELECT id, skill_id, role, kind, locator, path, label, url FROM skill_source"
                        + " WHERE skill_id = :skill AND role = 'origin'")
                .param("skill", skillId)
                .query((rs, rowNum) -> new SkillSource(
                        rs.getString("id"), rs.getString("skill_id"), rs.getString("role"),
                        rs.getString("kind"), rs.getString("locator"), rs.getString("path"),
                        rs.getString("label"), rs.getString("url")))
                .optional();
    }

    /**
     * Stores the origin, replacing an older one only when the connection actually changed. A
     * replacement cascades that source's snapshots: they were facts about a different platform.
     */
    public SkillSource putOrigin(String skillId, String kind, String locator, String path,
            String label, String url) {
        Optional<SkillSource> current = findOrigin(skillId);
        if (current.isPresent()) {
            SkillSource existing = current.get();
            if (existing.kind().equals(kind) && existing.locator().equals(locator)) {
                jdbc.sql("UPDATE skill_source SET path = :path, label = :label, url = :url"
                                + " WHERE id = :id")
                        .param("path", path)
                        .param("label", label)
                        .param("url", url)
                        .param("id", existing.id())
                        .update();
                return new SkillSource(existing.id(), existing.skillId(), existing.role(),
                        existing.kind(), existing.locator(), path, label, url);
            }
            jdbc.sql("DELETE FROM skill_source WHERE id = :id")
                    .param("id", existing.id())
                    .update();
        }
        String id = Ulid.generate();
        jdbc.sql("INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label,"
                        + " url, created_at) VALUES (:id, :skill, 'origin', :kind, :locator,"
                        + " :path, :label, :url, :now)")
                .param("id", id)
                .param("skill", skillId)
                .param("kind", kind)
                .param("locator", locator)
                .param("path", path)
                .param("label", label)
                .param("url", url)
                .param("now", Timestamps.now())
                .update();
        return new SkillSource(id, skillId, SkillSource.ORIGIN, kind, locator, path, label, url);
    }

    /**
     * Marks an existing snapshot as one the sync wrote, without touching revision or signals.
     *
     * <p>A replay can land on a version the refresh has already answered; only the mark is missing,
     * and rewriting the whole row would reset a settled status to pending.
     */
    public void markSyncSnapshot(String sourceId, String versionId) {
        jdbc.sql("UPDATE skill_source_snapshot SET written_by_sync = TRUE"
                        + " WHERE source_id = :source AND skill_version_id = :version")
                .param("source", sourceId)
                .param("version", versionId)
                .update();
    }

    public void putSnapshot(String sourceId, String versionId, String revision, String signals,
            String status, boolean writtenBySync) {
        jdbc.sql("INSERT INTO skill_source_snapshot (source_id, skill_version_id, revision,"
                        + " signals, status, fetched_at, written_by_sync)"
                        + " VALUES (:source, :version, :revision, :signals, :status, :now, :bySync)"
                        + " ON CONFLICT (source_id, skill_version_id) DO UPDATE SET"
                        + " revision = COALESCE(skill_source_snapshot.revision, EXCLUDED.revision),"
                        + " signals = CASE WHEN EXCLUDED.status IN ('error', 'stale')"
                        + "   THEN skill_source_snapshot.signals ELSE EXCLUDED.signals END,"
                        + " status = EXCLUDED.status, fetched_at = EXCLUDED.fetched_at,"
                        /* A refresh after the sync must not erase the fact: it says who wrote the
                           version, not who last talked to the upstream. */
                        + " written_by_sync = skill_source_snapshot.written_by_sync"
                        + "   OR EXCLUDED.written_by_sync")
                .param("source", sourceId)
                .param("version", versionId)
                .param("revision", revision)
                .param("signals", signals)
                .param("status", status)
                .param("now", Timestamps.now())
                .param("bySync", writtenBySync)
                .update();
    }

    /**
     * The origin held by the skill at {@code namespace/name}, if any.
     *
     * <p>v50's address preflight: a name already in the official namespace may be owned by a
     * different platform, and the sync must refuse to add a draft to it rather than replace its
     * origin (which would cascade away its snapshots).
     */
    public Optional<SkillSource> findByAddress(String namespaceId, String name) {
        /* A soft-deleted skill is not an origin conflict: the submission path refuses it with
           `skill_deleted`, which is the answer the operator can act on. Hiding it here is what
           keeps the preflight from reporting the wrong reason. */
        return jdbc.sql("SELECT src.id, src.skill_id, src.role, src.kind, src.locator, src.path,"
                        + " src.label, src.url FROM skill_source src"
                        + " JOIN skill k ON k.id = src.skill_id"
                        + " WHERE k.namespace_id = :namespace AND k.name = :name"
                        + "   AND k.deleted_at IS NULL AND src.role = 'origin'")
                .param("namespace", namespaceId)
                .param("name", name)
                .query((rs, rowNum) -> new SkillSource(
                        rs.getString("id"), rs.getString("skill_id"), rs.getString("role"),
                        rs.getString("kind"), rs.getString("locator"), rs.getString("path"),
                        rs.getString("label"), rs.getString("url")))
                .optional();
    }

    /**
     * The skill names already assigned to one upstream path, in the official namespace.
     *
     * <p>v50's stable address (ADR 0160): the first import chooses a platform name, and every later
     * revision of the same {@code (locator, path)} keeps it even if upstream renames the skill. More
     * than one row means the historical state is ambiguous; the caller falls back to computing the
     * name rather than guessing.
     */
    public List<String> syncedNames(String namespaceId, String kind, String locator, String path) {
        return jdbc.sql("SELECT k.name FROM skill_source src"
                        + " JOIN skill k ON k.id = src.skill_id"
                        + " WHERE k.namespace_id = :namespace AND k.deleted_at IS NULL"
                        + "   AND src.role = 'origin' AND src.kind = :kind"
                        + "   AND src.locator = :locator AND src.path = :path"
                        + " ORDER BY k.created_at, k.id")
                .param("namespace", namespaceId)
                .param("kind", kind)
                .param("locator", locator)
                .param("path", path)
                .query((rs, rowNum) -> rs.getString(1))
                .list();
    }

    /**
     * Every snapshot <strong>the sync wrote</strong> for this origin, whatever the version's state.
     * v50's sync uses it to answer "have I seen this upstream commit?"; discarded and revoked
     * versions count, so rejecting a sync draft is not undone by the next run. A snapshot the
     * publish-time refresh wrote is excluded — it names the version's HEAD, not a commit the sync
     * downloaded.
     */
    public java.util.List<Anchor> anchors(String namespaceId, String kind, String locator,
            String path) {
        /* Every snapshot the sync itself wrote on a live skill in the target namespace, whatever
           the version's state. `written_by_sync` is load-bearing: the refresh writes the upstream
           HEAD into a published version's snapshot, and taking that as "already seen" would skip a
           commit the sync has never downloaded. M12 does not name `skill_version` (M7 owns it), so
           it cannot order by version number; the caller pairs these with M7's newest version id.
           The namespace filter is load-bearing too: another account may declare the same GitHub
           origin, and its snapshot must not make the official skill look already-synced. */
        return jdbc.sql("SELECT src.skill_id, snap.skill_version_id AS version_id, snap.revision"
                        + " FROM skill_source src"
                        + " JOIN skill_source_snapshot snap ON snap.source_id = src.id"
                        + " JOIN skill k ON k.id = src.skill_id"
                        + " WHERE k.namespace_id = :namespace AND k.deleted_at IS NULL"
                        + "   AND snap.written_by_sync"
                        + "   AND src.role = 'origin' AND src.kind = :kind"
                        + "   AND src.locator = :locator AND src.path = :path")
                .param("namespace", namespaceId)
                .param("kind", kind)
                .param("locator", locator)
                .param("path", path)
                .query((rs, rowNum) -> new Anchor(
                        rs.getString("skill_id"), rs.getString("version_id"),
                        rs.getString("revision")))
                .list();
    }

    public record Anchor(String skillId, String versionId, String revision) {
    }

    /**
     * Whether the sync wrote this version's source facts.
     *
     * <p>The marker, not the row's existence: a published version also carries a snapshot once the
     * refresh has run, and treating that as a sync version would change how a rollback re-records
     * its origin. The publish path asks this instead of reading {@code skill_version.source},
     * because a replay can hand the sync a draft the official account uploaded by hand and that
     * draft must keep its own provenance.
     */
    public boolean hasSyncSnapshot(String versionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM skill_source_snapshot"
                        + " WHERE skill_version_id = :version AND written_by_sync)")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getBoolean(1))
                .single();
    }

    public Optional<SourceSnapshot> findSnapshot(String sourceId, String versionId) {
        return jdbc.sql("SELECT source_id, skill_version_id, revision, signals, status, fetched_at"
                        + " FROM skill_source_snapshot WHERE source_id = :source"
                        + " AND skill_version_id = :version")
                .param("source", sourceId)
                .param("version", versionId)
                .query((rs, rowNum) -> new SourceSnapshot(
                        rs.getString("source_id"), rs.getString("skill_version_id"),
                        rs.getString("revision"), rs.getString("signals"),
                        rs.getString("status"), rs.getString("fetched_at")))
                .optional();
    }

    public void putLicense(SkillLicense license) {
        jdbc.sql("INSERT INTO skill_version_license (version_id, spdx, name, copyright, status,"
                        + " license_text, source_path, source_url, detail, fetched_at)"
                        + " VALUES (:version, :spdx, :name, :copyright, :status, :text, :path,"
                        + " :url, :detail, :now)"
                        + " ON CONFLICT (version_id) DO UPDATE SET spdx = EXCLUDED.spdx,"
                        + " name = EXCLUDED.name, copyright = EXCLUDED.copyright,"
                        + " status = EXCLUDED.status, license_text = EXCLUDED.license_text,"
                        + " source_path = EXCLUDED.source_path, source_url = EXCLUDED.source_url,"
                        + " detail = EXCLUDED.detail, fetched_at = EXCLUDED.fetched_at")
                .param("version", license.versionId())
                .param("spdx", license.spdx())
                .param("name", license.name())
                .param("copyright", license.copyright())
                .param("status", license.status().wire())
                .param("text", license.licenseText())
                .param("path", license.sourcePath())
                .param("url", license.sourceUrl())
                .param("detail", license.detail())
                .param("now", Timestamps.now())
                .update();
    }

    public Optional<SkillLicense> findLicense(String versionId) {
        return jdbc.sql("SELECT version_id, spdx, name, copyright, status, license_text,"
                        + " source_path, source_url, detail, fetched_at"
                        + " FROM skill_version_license WHERE version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> new SkillLicense(
                        rs.getString("version_id"), rs.getString("spdx"), rs.getString("name"),
                        rs.getString("copyright"),
                        LicenseStatus.fromWire(rs.getString("status")),
                        rs.getString("license_text"), rs.getString("source_path"),
                        rs.getString("source_url"), rs.getString("detail"),
                        rs.getString("fetched_at")))
                .optional();
    }

    public Map<String, SkillLicense> licensesOf(Collection<String> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkillLicense> found = new HashMap<>();
        jdbc.sql("SELECT version_id, spdx, name, copyright, status, license_text, source_path,"
                        + " source_url, detail, fetched_at FROM skill_version_license"
                        + " WHERE version_id = ANY(:ids)")
                .param("ids", versionIds.toArray(String[]::new))
                .query((rs, rowNum) -> new SkillLicense(
                        rs.getString("version_id"), rs.getString("spdx"), rs.getString("name"),
                        rs.getString("copyright"),
                        LicenseStatus.fromWire(rs.getString("status")),
                        rs.getString("license_text"), rs.getString("source_path"),
                        rs.getString("source_url"), rs.getString("detail"),
                        rs.getString("fetched_at")))
                .list()
                .forEach(license -> found.put(license.versionId(), license));
        return found;
    }

    public int joins(String skillId) {
        return jdbc.sql("SELECT count(*) AS n FROM skill_library_join WHERE skill_id = :skill")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getInt("n"))
                .single();
    }

    /** Live versions whose source still needs a fetch: pending, failed, or older than the cutoff. */
    public java.util.List<Pending> pending(String cutoff) {
        return jdbc.sql("SELECT src.skill_id, snap.skill_version_id"
                        + " FROM skill_source_snapshot snap"
                        + " JOIN skill_source src ON src.id = snap.source_id"
                        + " JOIN skill k ON k.id = src.skill_id"
                        + " WHERE k.current_version_id = snap.skill_version_id"
                        + " AND (snap.status IN ('pending', 'error', 'stale')"
                        + " OR snap.fetched_at < :cutoff)")
                .param("cutoff", cutoff)
                .query((rs, rowNum) -> new Pending(
                        rs.getString("skill_id"), rs.getString("skill_version_id")))
                .list();
    }

    public record Pending(String skillId, String versionId) {
    }
}

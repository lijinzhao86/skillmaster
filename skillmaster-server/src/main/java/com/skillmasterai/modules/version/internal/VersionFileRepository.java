package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code version_file} reads and writes — the file half of M7's storage. */
final class VersionFileRepository {

    private final JdbcClient jdbc;

    VersionFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the manifest, one row per file.
     *
     * <p>A statement per file rather than a set-based insert. With the file ceiling where it is, inside
     * one transaction, on a local socket, that is a few milliseconds — and submitting is a rare
     * human action, not a hot path. Worth revisiting only if submit latency ever shows up.
     */
    void insertFiles(String versionId, Manifest manifest) {
        for (ManifestEntry entry : manifest.entries()) {
            jdbc.sql("""
                    INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary,
                                              mode)
                    VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary, :mode)
                    """)
                    .param("versionId", versionId)
                    .param("relpath", entry.relpath())
                    .param("blobSha256", entry.blobSha256())
                    .param("size", entry.size())
                    .param("isBinary", entry.isBinary() ? 1 : 0)
                    .param("mode", entry.mode())
                    .update();
        }
    }

    /**
     * One file row, written or replaced.
     *
     * <p>An upsert rather than delete-then-insert: the row's identity is {@code (version_id,
     * relpath)}, and replacing it in one statement is what keeps a concurrent reader from seeing
     * the file missing for the inside of a transaction. The caller has already decided whether this
     * path may be created; this method only records it.
     */
    void putFile(String versionId, ManifestEntry entry) {
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary, mode)
                VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary, :mode)
                ON CONFLICT (version_id, relpath) DO UPDATE
                SET blob_sha256 = EXCLUDED.blob_sha256,
                    size = EXCLUDED.size,
                    is_binary = EXCLUDED.is_binary,
                    mode = EXCLUDED.mode
                """)
                .param("versionId", versionId)
                .param("relpath", entry.relpath())
                .param("blobSha256", entry.blobSha256())
                .param("size", entry.size())
                .param("isBinary", entry.isBinary() ? 1 : 0)
                .param("mode", entry.mode())
                .update();
    }

    /** @return whether a row was removed */
    int deleteFile(String versionId, String relpath) {
        return jdbc.sql("DELETE FROM version_file WHERE version_id = :versionId"
                        + " AND relpath = :relpath")
                .param("versionId", versionId)
                .param("relpath", relpath)
                .update();
    }

    /**
     * Renames a file row in place. The blob is untouched — content addressing means a move is one
     * column's value changing, not bytes being copied (v7 TD §安全边界).
     *
     * @return whether a row was written
     */
    int renameFile(String versionId, String from, String to) {
        // **A move takes its permissions from where it lands** (v51, ADR 0166). Updating the path
        // alone left a script that left `scripts/` executable, and a script that entered it plain —
        // the one write path that could still disagree with the rule.
        return jdbc.sql("UPDATE version_file SET relpath = :to, mode = :mode"
                        + " WHERE version_id = :versionId AND relpath = :from")
                .param("versionId", versionId)
                .param("from", from)
                .param("to", to)
                .param("mode", com.skillmasterai.modules.version.ExecutableMode.of(to))
                .update();
    }

    /**
     * Copies a file row, sharing the source's blob.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is not used: the caller has already refused a target that
     * exists ({@code file_already_exists}), so a conflict here is a bug rather than a race the
     * command can absorb — and the unique constraint says so loudly.
     */
    void copyFile(String versionId, ManifestEntry source, String to) {
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary, mode)
                VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary, :mode)
                """)
                .param("versionId", versionId)
                .param("relpath", to)
                .param("blobSha256", source.blobSha256())
                .param("size", source.size())
                .param("isBinary", source.isBinary() ? 1 : 0)
                .param("mode", com.skillmasterai.modules.version.ExecutableMode.of(to))
                .update();
    }

    /**
     * A version's manifest.
     *
     * <p>No {@code ORDER BY}: {@link Manifest} sorts explicitly, and it is the only thing that may
     * — the digest and the served manifest have to agree, and the database's collation is one of
     * the three orders that would disagree.
     */
    Manifest filesOf(String versionId) {
        /* **The mode is derived here, not read back** (v51, ADR 0166). The column is written by
           every write path and a migration aligned the old rows; reading it would only be a second
           place that has to stay true, and a manifest is what every consumer reads the bit from.
           The `relpath` constructor derives it. */
        List<ManifestEntry> entries = jdbc.sql("""
                SELECT relpath, blob_sha256, size, is_binary
                FROM version_file WHERE version_id = :versionId
                """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new ManifestEntry(
                        rs.getString("relpath"),
                        rs.getString("blob_sha256"),
                        rs.getLong("size"),
                        rs.getInt("is_binary") != 0))
                .list();
        return Manifest.of(entries);
    }
}

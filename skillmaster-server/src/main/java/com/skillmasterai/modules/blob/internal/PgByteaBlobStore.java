package com.skillmasterai.modules.blob.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.blob.BlobNotFoundException;
import com.skillmasterai.modules.blob.BlobStore;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bytes in PostgreSQL, in the {@code blob_content} table (ADR 0010 decision 2).
 *
 * <p>Public but confined: it lives in {@code internal}, and the architecture test forbids any
 * package outside this module from referencing {@code internal}. Public rather than
 * package-private because a module's wiring class sits in the parent package and could not
 * otherwise construct it — package-private does not cross a package boundary, and a subpackage is
 * a boundary. The guarantee that matters ("nothing outside this module knows what the backend is")
 * is the architecture rule's, not javac's.
 *
 * <p>Both writes are {@code ON CONFLICT DO NOTHING} rather than a read-then-write: identical
 * content is the normal case (every republish, and any shared file between two skills), and a
 * check-then-insert would race between concurrent publishes of the same content.
 */
public final class PgByteaBlobStore implements BlobStore {

    private final JdbcClient jdbc;

    public PgByteaBlobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BlobRef put(byte[] bytes) {
        String sha256 = Sha256Hex.of(bytes);

        // Metadata first: blob_content.sha256 references blob.sha256, and the digest is computed
        // here rather than accepted from the caller, so the two can never disagree.
        jdbc.sql("""
                INSERT INTO blob (sha256, size, created_at)
                VALUES (:sha256, :size, :createdAt)
                ON CONFLICT (sha256) DO NOTHING
                """)
                .param("sha256", sha256)
                .param("size", bytes.length)
                .param("createdAt", Timestamps.now())
                .update();

        jdbc.sql("""
                INSERT INTO blob_content (sha256, bytes)
                VALUES (:sha256, :bytes)
                ON CONFLICT (sha256) DO NOTHING
                """)
                .param("sha256", sha256)
                .param("bytes", bytes)
                .update();

        return new BlobRef(sha256, bytes.length);
    }

    @Override
    public byte[] get(String sha256Hex) {
        return jdbc.sql("SELECT bytes FROM blob_content WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query((rs, rowNum) -> rs.getBytes("bytes"))
                .optional()
                .orElseThrow(() -> new BlobNotFoundException(sha256Hex));
    }

    /**
     * Reads {@code blob_content.bytes} in windows with {@code substring}.
     *
     * <p>A window query per chunk rather than one row streamed from a held connection: this store
     * borrows a connection per call, and holding one open for the life of a response would pin a
     * pool slot for as long as a slow client reads — or does not read. Thirty-two kilobytes keeps
     * the number of round trips low for a whole file and the time to first byte low for a partial
     * one, which are the two things the budget asks for.
     *
     * <p>The size is read first so the stream knows when PostgreSQL's one-past-the-end empty window
     * has been reached without asking for it. The row is immutable once written (content
     * addressing), so the size cannot move underneath the stream.
     */
    @Override
    public java.io.InputStream open(String sha256Hex) {
        Long size = jdbc.sql("SELECT size FROM blob WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BlobNotFoundException(sha256Hex));
        return new WindowedBlobInputStream(jdbc, sha256Hex, size);
    }

    @Override
    public boolean exists(String sha256Hex) {
        return jdbc.sql("SELECT 1 FROM blob WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Override
    public java.util.List<String> containing(Set<String> shas, String needle) {
        if (shas.isEmpty()) {
            return java.util.List.of();
        }
        // `position(substring in string)` rather than `strpos`: PostgreSQL has strpos only for
        // text, and the whole point of comparing bytes is that the needle may contain anything.
        // The needle travels as a bytea parameter, so no text conversion can fail on it.
        return jdbc.sql("""
                SELECT sha256 FROM blob_content
                WHERE sha256 = ANY(:shas)
                  AND position(CAST(:needle AS bytea) IN bytes) > 0
                """)
                .param("shas", shas.toArray(String[]::new))
                .param("needle", needle.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .query(String.class)
                .list();
    }

    /**
     * Deletes the metadata row; {@code blob_content.sha256} cascades from it, so the bytes go too.
     *
     * <p>Only {@code blob} is named here on purpose — this is M6's table, and the set of digests
     * still in use arrives as a parameter rather than as a subquery over M7's {@code version_file}.
     *
     * <p>{@code <> ALL} over an empty array is true for every row, which is what makes "nothing is
     * referenced" behave as "delete everything" without a branch.
     */
    @Override
    public int deleteUnreferenced(Set<String> referenced, String cutoff) {
        // Both halves are load-bearing: the first is what a sweep is for, the second is the grace
        // period that keeps a write's blob alive between its insert and its version_file row.
        return jdbc.sql("DELETE FROM blob WHERE sha256 <> ALL (:referenced)"
                        + " AND created_at < :cutoff")
                .param("referenced", referenced.toArray(String[]::new))
                .param("cutoff", cutoff)
                .update();
    }
}

package com.skillmasterai.modules.blob;

/**
 * Where skill file bytes live, addressed by their SHA-256.
 *
 * <p>This seam exists from v1 even though there is exactly one implementation (ADR 0010 后果:
 * "这层接缝必须现在就做"). The intent is that moving bytes elsewhere later — object storage, a
 * faster tier — is a configuration change rather than a change to the publish path. Three
 * implementations are anticipated in order: PostgreSQL {@code bytea} now, object storage later,
 * then a caching wrapper over that.
 *
 * <p>Content addressing is entirely inside this module. {@link #put} computes the digest itself,
 * so no caller can record a {@code blob_sha256} that does not describe the bytes it stored —
 * which is what makes {@link #get} safe to trust and what makes deduplication automatic.
 */
public interface BlobStore {

    /**
     * Stores the given bytes if they are not already present.
     *
     * <p>Idempotent: the same content stored twice occupies one row. Two skills that share a
     * {@code references/} directory therefore store one copy (ADR 0005).
     */
    BlobRef put(byte[] bytes);

    /**
     * @return the stored bytes
     * @throws BlobNotFoundException if no blob has that digest
     */
    byte[] get(String sha256Hex);

    /**
     * The stored bytes as a stream, read in bounded chunks.
     *
     * <p><strong>Why this exists at all, given {@link #get}.</strong> A {@code cat} piped into
     * {@code head} stops reading after a few lines, and a server that buffered the whole file first
     * would have moved a megabyte to deliver 200 bytes — the measurement v7's performance budget is
     * written against. The stream lets the response stop when the client does (v7 TD §流式与提前退出).
     *
     * <p>The implementation may fetch in chunks; nothing promises one statement per file. What it
     * promises is that the bytes are the blob's bytes, unaltered, and that a closed stream stops
     * fetching. The caller owns the stream and must close it.
     *
     * @throws BlobNotFoundException if no blob has that digest
     */
    java.io.InputStream open(String sha256Hex);

    boolean exists(String sha256Hex);

    /**
     * Deletes every stored blob whose digest is not in {@code referenced} and whose {@code created_at}
     * is older than {@code cutoff}, and reports how many.
     *
     * <p>Reference counting is M7's — {@code version_file} is what references a blob, and M7 owns
     * that table — but the rows being deleted are M6's, so the decision and the deletion are split:
     * M7 passes the set it still references, and M6 acts on it. §2.5 rule 1 permits no arrangement
     * in which one statement names both modules' tables.
     *
     * <p>An empty set means nothing is referenced, so every blob is deleted. That falls out of the
     * SQL rather than being a special case, and it is the correct reading: a blob nothing points
     * at is exactly what this method exists to remove.
     *
     * <p>Not idempotent in the sense of being free to call twice, but safe: the second call finds
     * nothing left to delete. <strong>The caller must already be in a transaction</strong>, and it
     * must be the same one that made the set authoritative — §3.3 point 5 and ADR 0010 decision 4
     * require reclamation to commit with the version change that orphaned the bytes, never as a
     * separate reconciliation pass.
     *
     * <p><strong>The cutoff is a grace period, not a filter.</strong> A writer stores bytes and then
     * references them, in that order and inside one transaction (v7 TD §blob 回收). A sweep running
     * concurrently with that transaction sees the blob but not yet its reference, so without a grace
     * period it would delete bytes a committed version is about to point at. Ten minutes is three
     * orders of magnitude longer than the put-to-reference window; a blob younger than that is
     * simply not swept, whether or not anything points at it yet.
     *
     * @param referenced digests to keep, lowercase hex
     * @param cutoff     RFC3339 UTC; a blob created at or after this instant is kept regardless
     * @return how many blobs were deleted
     */
    int deleteUnreferenced(java.util.Set<String> referenced, String cutoff);

    /**
     * Which of these blobs contain a fixed string, byte for byte.
     *
     * <p>The prefilter {@code grep -F} runs in the database: a primary-key lookup over one version's
     * digests, with {@code strpos} deciding, so only candidates are read into the application (v7 TD
     * §grep). Case-sensitive by definition — {@code -i} cannot use this, because PostgreSQL's
     * {@code lower()} folds ASCII only and would silently miss the rest.
     *
     * <p>Working on {@code bytea} rather than text is also what makes it safe on anything: a NUL or
     * an invalid UTF-8 sequence is just bytes to {@code strpos}, so the query cannot fail on content
     * the caller is allowed to hold.
     *
     * @param shas   the version's blob digests, lowercase hex
     * @param needle the fixed string, non-empty
     * @return the subset of {@code shas} whose bytes contain it
     */
    java.util.List<String> containing(java.util.Set<String> shas, String needle);

    /**
     * @param sha256Hex lowercase hex, as produced by {@link com.skillmasterai.common.Sha256Hex}
     * @param size      the byte count, so callers can size a response without reading the bytes
     */
    record BlobRef(String sha256Hex, long size) {
    }
}

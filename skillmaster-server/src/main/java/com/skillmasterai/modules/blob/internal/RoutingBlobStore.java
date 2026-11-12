package com.skillmasterai.modules.blob.internal;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.blob.BlobNotFoundException;
import com.skillmasterai.modules.blob.BlobSettings;
import com.skillmasterai.modules.blob.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides which backend holds a blob, and hides the decision from every caller (ADR 0130).
 *
 * <p><strong>The rule is size and only size.</strong> A blob larger than
 * {@link BlobSettings#thresholdBytes()} goes to the private bucket; everything else stays in
 * {@code blob_content}. Content type is never consulted: a UTF-8 probe that classified a large
 * ASCII file as text would send exactly the content the database should not carry back into it, and
 * a storage location that depended on such a probe could put the same bytes in two places.
 *
 * <p><strong>Writes never hold a large file in the heap.</strong> A byte array above the threshold
 * is written to one temporary file, hashed while it is written, and uploaded from that file. A
 * stream is always spooled the same way; the caller's declared size is an early refusal, not a
 * promise about the stream. The temporary file is removed in every outcome.
 *
 * <p><strong>Deletion is an outbox.</strong> The transaction that removes a {@code blob} row also
 * writes the object's key to {@code blob_deletion}; the object is deleted after that transaction
 * commits, and a failed deletion leaves the row for the next sweep. OSS cannot join a database
 * transaction, and pretending it can is how an object gets deleted while the row that pointed at it
 * rolls back.
 */
public final class RoutingBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingBlobStore.class);

    /** Matches {@code grep}'s text ceiling; a larger text blob is not scanned (ADR 0133). */
    private static final long TEXT_SCAN_MAX_BYTES = 16L * 1024 * 1024;

    private static final int BUFFER = 64 * 1024;

    private final JdbcClient jdbc;
    private final PgByteaBlobStore pg;
    private final OssBlobStore oss;
    private final BlobSettings settings;
    private final BlobDeletionDrain drain;

    /**
     * @param oss null when {@link BlobSettings#ossEnabled()} is false; the store then keeps every
     *            blob in PostgreSQL, including ones above the threshold, which is what a local run
     *            and the default test profile need
     */
    public RoutingBlobStore(JdbcClient jdbc, PgByteaBlobStore pg, OssBlobStore oss,
            BlobSettings settings,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.pg = pg;
        this.oss = oss;
        this.settings = settings;
        this.drain = new BlobDeletionDrain(jdbc, oss, transactions, this::lockBlobRow);
    }

    @Override
    public void putObject(String key, java.nio.file.Path file, long size) {
        if (oss == null) {
            throw new IllegalStateException(
                    "object storage is off; an export cannot leave this server");
        }
        oss.put(key, file, size);
    }

    @Override
    public void deleteObject(String key) {
        if (oss == null) {
            throw new IllegalStateException(
                    "object storage is off; there is no named object to delete");
        }
        oss.delete(key);
    }

    @Override
    public BlobRef put(byte[] bytes) {
        // The same backstop the streaming entries have. A byte array is already in the heap, so
        // this is not about memory: it is the last place that can refuse an oversized body before
        // it reaches the bucket, and a caller that forgot its own check must not write an object the
        // product would never accept.
        if (bytes.length > settings.maxBytes()) {
            throw tooLarge(bytes.length, settings.maxBytes());
        }
        if (bytes.length > settings.thresholdBytes()) {
            Spooled spooled = writeTemp(bytes);
            try {
                return storeSpooled(spooled);
            } finally {
                deleteQuietly(spooled.file());
            }
        }
        return storeInDatabase(bytes);
    }

    @Override
    public BlobRef put(InputStream bytes, long size) {
        Spooled spooled = spool(bytes);
        try {
            return storeSpooled(spooled);
        } finally {
            deleteQuietly(spooled.file());
        }
    }

    @Override
    public BlobRef put(Path file, long size) {
        // The file is the authority on its own length; a caller's number is only a hint and a wrong
        // one could make a large file take the small-file path and be read into the heap.
        long actual;
        try {
            actual = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not size a spooled upload", e);
        }
        if (actual > settings.maxBytes()) {
            throw tooLarge(actual, settings.maxBytes());
        }
        String sha256 = hashFile(file);
        Optional<BlobRow> existing = rowOf(sha256);
        if (existing.isPresent()) {
            return new BlobRef(sha256, existing.get().size());
        }
        if (actual <= settings.thresholdBytes() || oss == null) {
            return storeInDatabase(read(file));
        }
        // Serialise with the outbox drain: without this, a drain that checked before this row was
        // committed could delete the object this upload is about to make live.
        lockBlobRow(sha256);
        String location = OssBlobStore.keyOf(sha256);
        oss.put(location, file, actual);
        jdbc.sql("""
                        INSERT INTO blob (sha256, size, created_at, storage, location)
                        VALUES (:sha256, :size, :createdAt, 'oss', :location)
                        ON CONFLICT (sha256) DO NOTHING
                        """)
                .param("sha256", sha256)
                .param("size", actual)
                .param("createdAt", Timestamps.now())
                .param("location", location)
                .update();
        // The object is live again: a queued deletion for this digest would otherwise remove it on
        // the next sweep, after a re-upload of the same content.
        clearQueuedDeletion(sha256);
        return new BlobRef(sha256, actual);
    }

    @Override
    public byte[] get(String sha256Hex) {
        BlobRow row = rowOf(sha256Hex).orElseThrow(() -> new BlobNotFoundException(sha256Hex));
        if (BlobRow.OSS.equals(row.storage())) {
            return requireOss().get(row.location());
        }
        return pg.get(sha256Hex);
    }

    @Override
    public InputStream open(String sha256Hex) {
        BlobRow row = rowOf(sha256Hex).orElseThrow(() -> new BlobNotFoundException(sha256Hex));
        if (BlobRow.OSS.equals(row.storage())) {
            return requireOss().open(row.location());
        }
        return pg.open(sha256Hex);
    }

    @Override
    public boolean exists(String sha256Hex) {
        return rowOf(sha256Hex).isPresent();
    }

    @Override
    public List<String> containing(Set<String> shas, String needle) {
        if (shas.isEmpty()) {
            return List.of();
        }
        List<BlobRow> rows = jdbc.sql("""
                        SELECT sha256, storage, location, size FROM blob
                        WHERE sha256 = ANY(:shas)
                        """)
                .param("shas", shas.toArray(String[]::new))
                .query((rs, rowNum) -> new BlobRow(
                        rs.getString("sha256"),
                        rs.getString("storage"),
                        rs.getString("location"),
                        rs.getLong("size")))
                .list();

        Set<String> inDatabase = new LinkedHashSet<>();
        for (BlobRow row : rows) {
            if (BlobRow.DB.equals(row.storage())) {
                inDatabase.add(row.sha256());
            }
        }
        List<String> hits = new ArrayList<>(pg.containing(inDatabase, needle));

        byte[] needleBytes = needle.getBytes(StandardCharsets.UTF_8);
        for (BlobRow row : rows) {
            if (!BlobRow.OSS.equals(row.storage())
                    || row.size() > TEXT_SCAN_MAX_BYTES
                    || row.size() < needleBytes.length
                    || oss == null) {
                continue;
            }
            try (InputStream in = oss.open(row.location())) {
                if (contains(in, needleBytes)) {
                    hits.add(row.sha256());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not scan " + row.sha256(), e);
            }
        }
        return hits;
    }

    /**
     * Removes unreferenced rows, records the OSS objects among them for deletion, and lets the
     * object deletion run after the transaction commits.
     *
     * <p>Draining here would be wrong: the DELETE above may still roll back, and an object deleted
     * for a row that came back is a row pointing at nothing. The after-commit hook is skipped when
     * there is no transaction to hook onto, in which case the drain runs immediately.
     */
    @Override
    public int deleteUnreferenced(Set<String> referenced, String cutoff) {
        String now = Timestamps.now();
        jdbc.sql("""
                        INSERT INTO blob_deletion (sha256, location, created_at)
                        SELECT sha256, location, :now FROM blob
                        WHERE sha256 <> ALL (:referenced)
                          AND created_at < :cutoff
                          AND storage = 'oss'
                        ON CONFLICT (sha256) DO NOTHING
                        """)
                .param("referenced", referenced.toArray(String[]::new))
                .param("cutoff", cutoff)
                .param("now", now)
                .update();

        int deleted = jdbc.sql("DELETE FROM blob WHERE sha256 <> ALL (:referenced)"
                        + " AND created_at < :cutoff")
                .param("referenced", referenced.toArray(String[]::new))
                .param("cutoff", cutoff)
                .update();

        drain.afterCommit();
        return deleted;
    }

    /** Deletes pending OSS objects, at most one batch per call (see {@link BlobDeletionDrain}). */
    public void drainDeletions() {
        drain.drain();
    }

    private BlobRef storeInDatabase(byte[] bytes) {
        String sha256 = Sha256Hex.of(bytes);
        Optional<BlobRow> existing = rowOf(sha256);
        if (existing.isPresent()) {
            return new BlobRef(sha256, existing.get().size());
        }
        // The blob row and its bytes are written in the caller's transaction, which is what makes
        // a publish atomic: there is no window in which a committed version points at absent bytes.
        return pg.put(bytes);
    }

    private BlobRef storeSpooled(Spooled spooled) {
        Optional<BlobRow> existing = rowOf(spooled.sha256());
        if (existing.isPresent()) {
            return new BlobRef(spooled.sha256(), existing.get().size());
        }
        if (spooled.size() <= settings.thresholdBytes()) {
            byte[] bytes = read(spooled.file());
            return storeInDatabase(bytes);
        }
        if (oss == null) {
            // No bucket configured: keep the bytes in the database rather than refusing a legal
            // upload. A deployment that expects large files should enable OSS; this path is for
            // local runs and tests.
            return storeInDatabase(read(spooled.file()));
        }

        lockBlobRow(spooled.sha256());
        String location = OssBlobStore.keyOf(spooled.sha256());
        oss.put(location, spooled.file(), spooled.size());
        jdbc.sql("""
                        INSERT INTO blob (sha256, size, created_at, storage, location)
                        VALUES (:sha256, :size, :createdAt, 'oss', :location)
                        ON CONFLICT (sha256) DO NOTHING
                        """)
                .param("sha256", spooled.sha256())
                .param("size", spooled.size())
                .param("createdAt", Timestamps.now())
                .param("location", location)
                .update();
        clearQueuedDeletion(spooled.sha256());
        return new BlobRef(spooled.sha256(), spooled.size());
    }

    /**
     * The per-digest advisory lock that makes "is this object live?" and "may I delete it?"
     * one decision. `pg_advisory_xact_lock` is released at commit/rollback, so it covers the
     * caller's whole upload transaction; the drain takes the same lock inside its own transaction.
     * Outside a transaction (a direct call in a test) there is nothing to hold the lock until, so
     * the upload path stays lock-free rather than taking a lock it would drop immediately.
     */
    private void lockBlobRow(String sha256) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:sha256))")
                .param("sha256", sha256)
                .query((rs, rowNum) -> Boolean.TRUE)
                .optional();
    }

    /** A digest that is live again must not have a deletion waiting for it. */
    private void clearQueuedDeletion(String sha256) {
        jdbc.sql("DELETE FROM blob_deletion WHERE sha256 = :sha256")
                .param("sha256", sha256)
                .update();
    }

    private Spooled spool(InputStream in) {
        Path temp = null;
        try {
            Path dir = Path.of(settings.tempDir());
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "skillmaster-upload-", ".bin");
            MessageDigest digest = Sha256Hex.newDigest();
            long total = 0;
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > settings.maxBytes()) {
                        throw tooLarge(total, settings.maxBytes());
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            return new Spooled(temp, HexFormat.of().formatHex(digest.digest()), total);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("could not spool an upload", e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private Spooled writeTemp(byte[] bytes) {
        Path temp = null;
        try {
            Path dir = Path.of(settings.tempDir());
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "skillmaster-upload-", ".bin");
            Files.write(temp, bytes);
            return new Spooled(temp, Sha256Hex.of(bytes), bytes.length);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("could not spool an upload", e);
        }
    }

    private static String hashFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = Sha256Hex.newDigest();
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("could not hash a spooled upload", e);
        }
    }

    private byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read a spooled upload", e);
        }
    }

    private Optional<BlobRow> rowOf(String sha256Hex) {
        return jdbc.sql("SELECT sha256, storage, location, size FROM blob WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query((rs, rowNum) -> new BlobRow(
                        rs.getString("sha256"),
                        rs.getString("storage"),
                        rs.getString("location"),
                        rs.getLong("size")))
                .optional();
    }

    private OssBlobStore requireOss() {
        if (oss == null) {
            throw new IllegalStateException(
                    "a blob is stored in OSS but object storage is not configured");
        }
        return oss;
    }

    /** A streaming substring search: the blob is never held whole, only a needle-sized overlap. */
    private static boolean contains(InputStream in, byte[] needle) throws IOException {
        if (needle.length == 0) {
            return true;
        }
        byte[] buffer = new byte[BUFFER + needle.length - 1];
        int kept = 0;
        int read;
        while ((read = in.read(buffer, kept, buffer.length - kept)) != -1) {
            int length = kept + read;
            for (int i = 0; i + needle.length <= length; i++) {
                boolean match = true;
                for (int j = 0; j < needle.length; j++) {
                    if (buffer[i + j] != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
            kept = Math.min(needle.length - 1, length);
            System.arraycopy(buffer, length - kept, buffer, 0, kept);
        }
        return false;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("could not remove temporary upload {}", file, e);
        }
    }

    private static RequestException tooLarge(long actual, long limit) {
        return new RequestException(ErrorCode.INVALID_UPLOAD,
                "the file is " + com.skillmasterai.common.Bytes.human(actual)
                        + ", above the per-file limit of "
                        + com.skillmasterai.common.Bytes.human(limit),
                List.of(new ApiError.Detail("file", "file_too_large", actual, limit)));
    }

    private record Spooled(Path file, String sha256, long size) {
    }

    private record BlobRow(String sha256, String storage, String location, long size) {
        static final String DB = "db";
        static final String OSS = "oss";
    }

    private record BlobDeletion(String sha256, String location) {
    }
}

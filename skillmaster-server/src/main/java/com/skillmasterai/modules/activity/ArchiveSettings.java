package com.skillmasterai.modules.activity;

import java.nio.file.Path;

/**
 * Where expired activity partitions are staged before upload (v58).
 *
 * <p>A directory, not a bucket: the file is produced here and handed to object storage through
 * {@code BlobStore.putObject}. Keeping the staging copy local means a failed upload leaves a file
 * an operator can retry, rather than a half-written object in the bucket.
 *
 * <p>Keep the directory on this host, private to the service account, and not on a shared or
 * world-writable volume: the path is resolved by name, so a shared parent would let another user
 * race a symbolic link in between the checks and the write.
 *
 * <p>Each partition also leaves a zero-byte {@code <partition>.lock} here. It is bounded (one per
 * archived week) and is not cleaned up on purpose: deleting a released lock file lets a later
 * process create a new inode for the same name and lock alongside a waiter still holding the old
 * one.
 *
 * @param dir absolute or working-directory-relative; blank means the JVM's temp directory
 */
public record ArchiveSettings(String dir) {

    public ArchiveSettings {
        if (dir == null || dir.isBlank()) {
            dir = Path.of(System.getProperty("java.io.tmpdir"), "skillmaster-archive").toString();
        }
    }

    public Path directory() {
        return Path.of(dir);
    }
}

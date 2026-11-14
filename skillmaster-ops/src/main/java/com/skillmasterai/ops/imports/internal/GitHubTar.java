package com.skillmasterai.ops.imports.internal;

import com.skillmasterai.ops.imports.Spdx;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Streams a repository tarball and keeps only what the sync may read: files under the configured
 * skill directory, the license files on that directory's ancestry, and the one file a source's
 * {@code license:} setting names explicitly.
 *
 * <p><strong>This sees hostile input.</strong> Everything is checked before it is kept: entry
 * types (symlinks, hard links and devices are refused where they would be kept, ignored where they
 * would not), relative paths (no absolute paths, no {@code ..}),
 * compressed and decompressed size ceilings, entry count, file count, per-file and total bytes.
 * Any violation stops the whole entry — half an archive is never handed to the ingest path.
 *
 * <p>The tarball GitHub serves has one top-level directory ({@code repo-<sha>/}); it is stripped
 * once so the paths match the repository's own.
 */
public final class GitHubTar {

    /** The ceilings one sync entry may spend on an archive. */
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, long maxRepoBytes,
            long maxUnpackedBytes) {
    }

    public record Entry(String path, byte[] content, boolean executable) {
    }

    public record Result(List<Entry> files, List<Entry> licenses, String error) {

        public static Result failed(String reason) {
            return new Result(List.of(), List.of(), reason);
        }
    }

    /**
     * A tar is a sequence of entries, and each one costs at least a 512-byte header. Without an
     * entry ceiling a tarball of millions of empty entries would exhaust memory before any size
     * ceiling noticed: the payloads are zero and the headers are not charged to {@code unpacked}.
     * Derived from the file ceiling so a deployment that raises it also raises this.
     */
    private static final int MIN_ENTRIES = 1_000;

    private GitHubTar() {
    }

    public static Result extract(InputStream compressed, String skillPath, Limits limits) {
        return extract(compressed, skillPath, limits, "");
    }

    /**
     * @param licensePath a repository-relative file to retain as the license even when it sits
     *                    outside the skill directory's ancestry; empty means "find the nearest".
     */
    public static Result extract(InputStream compressed, String skillPath, Limits limits,
            String licensePath) {
        String dir = normalize(skillPath);
        String explicitLicense = normalize(licensePath);
        int maxEntries = Math.max(MIN_ENTRIES, limits.maxFiles() * 50);
        try (CountingInputStream counted = new CountingInputStream(compressed, limits.maxRepoBytes());
                GzipCompressorInputStream gzip = new GzipCompressorInputStream(counted);
                TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            List<Entry> files = new ArrayList<>();
            List<Entry> licenses = new ArrayList<>();
            long total = 0;
            long unpacked = 0;
            int count = 0;
            int entries = 0;
            String root = null;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = stripDotSlash(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                if (name.startsWith("/") || name.startsWith("./") || name.equals("..")
                        || name.startsWith("../")) {
                    return Result.failed("unsafe_archive");
                }
                String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (first.isEmpty() || first.equals(".") || first.equals("..")) {
                    return Result.failed("unsafe_archive");
                }
                if (root == null) {
                    root = first;
                }
                if (!name.equals(root) && !name.startsWith(root + "/")) {
                    return Result.failed("unsafe_archive");
                }
                String rel = name.equals(root) ? "" : name.substring(root.length() + 1);
                if (rel.isEmpty()) {
                    continue;
                }
                if (!safe(rel)) {
                    return Result.failed("unsafe_archive");
                }
                if (++entries > maxEntries) {
                    return Result.failed("too_large");
                }
                // Every entry costs a header; charging it here is what makes an archive of empty
                // entries hit the ceiling instead of the heap.
                unpacked += 512;
                if (unpacked > limits.maxUnpackedBytes()) {
                    return Result.failed("too_large");
                }
                boolean wanted = under(rel, dir);
                boolean license = (licenseName(rel) && licenseRelevant(rel, dir))
                        || (!explicitLicense.isEmpty() && rel.equals(explicitLicense));
                boolean executable = !entry.isDirectory() && (entry.getMode() & 0111) != 0;
                /* A link or device is refused only where it could be retained. Repositories
                   routinely carry a root CLAUDE.md -> AGENTS.md; blocking the whole entry for a
                   file this extraction never reads would make most real repositories unsyncable.
                   Nothing here follows a link: outside entries are skipped, never resolved. This
                   check runs before the directory skip so a link whose name ends in `/` cannot be
                   mistaken for a directory. */
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    if (wanted || license) {
                        return Result.failed("unsafe_archive");
                    }
                    continue;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long remaining = limits.maxUnpackedBytes() - unpacked;
                byte[] content;
                try {
                    if (wanted || license) {
                        content = read(tar, limits.maxFileBytes());
                        unpacked += content.length;
                    } else {
                        unpacked += drain(tar, remaining);
                        content = null;
                    }
                    if (unpacked > limits.maxUnpackedBytes()) {
                        return Result.failed("too_large");
                    }
                } catch (Oversize oversize) {
                    return Result.failed("too_large");
                }
                if (license) {
                    licenses.add(new Entry(rel, content, executable));
                }
                if (wanted) {
                    count++;
                    total += content.length;
                    if (count > limits.maxFiles() || total > limits.maxTotalBytes()) {
                        return Result.failed("too_large");
                    }
                    files.add(new Entry(rel, content, executable));
                }
            }
            return new Result(files, licenses, null);
        } catch (CeilingExceeded ceiling) {
            return Result.failed("too_large");
        } catch (IOException failure) {
            return Result.failed("upstream_io");
        }
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** The stored path is the skill directory itself or something under it. */
    private static boolean under(String path, String dir) {
        return dir.isEmpty() || path.equals(dir) || path.startsWith(dir + "/");
    }

    private static boolean licenseName(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        return Spdx.LICENSE_NAMES.contains(base);
    }

    /**
     * For automatic detection a license matters only if it is on the skill directory's ancestry:
     * the reader walks from the directory upward, so a license under a sibling directory (a
     * vendored dependency, say) can never be the answer. An explicit {@code license:} path is a
     * separate case, handled by {@link #extract(InputStream, String, Limits, String)}. Keeping only
     * these is also the memory bound the TD promises — a repository can hold thousands of LICENSE
     * files and none of the others is ever read.
     */
    private static boolean licenseRelevant(String rel, String dir) {
        String parent = parentOf(rel);
        if (dir.isEmpty()) {
            return parent.isEmpty();
        }
        return parent.isEmpty() || dir.equals(parent) || dir.startsWith(parent + "/");
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean safe(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")) {
            return false;
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String stripDotSlash(String name) {
        String value = name == null ? "" : name;
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    private static byte[] read(TarArchiveInputStream tar, long max)
            throws IOException, Oversize {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long seen = 0;
        int read;
        while ((read = tar.read(buffer)) != -1) {
            seen += read;
            if (seen > max) {
                throw new Oversize();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Reads and discards one entry, stopping at the remaining budget. */
    private static long drain(TarArchiveInputStream tar, long remaining)
            throws IOException, Oversize {
        byte[] buffer = new byte[8192];
        long seen = 0;
        int read;
        while ((read = tar.read(buffer)) != -1) {
            seen += read;
            if (seen > remaining) {
                throw new Oversize();
            }
        }
        return seen;
    }

    private static final class Oversize extends Exception {
    }

    /** The compressed ceiling was crossed; distinct from a network failure so it can say so. */
    private static final class CeilingExceeded extends IOException {
        private CeilingExceeded() {
            super("archive exceeds its compressed ceiling");
        }
    }

    /** Counts the compressed bytes actually read, so a tarball cannot outgrow its ceiling. */
    private static final class CountingInputStream extends FilterInputStream {

        private final long max;
        private long seen;

        private CountingInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                bump(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                bump(read);
            }
            return read;
        }

        private void bump(int count) throws IOException {
            seen += count;
            if (seen > max) {
                throw new CeilingExceeded();
            }
        }
    }
}

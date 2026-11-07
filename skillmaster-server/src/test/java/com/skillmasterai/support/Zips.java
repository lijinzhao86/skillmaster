package com.skillmasterai.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Builds zips for tests.
 *
 * <p>Uses commons-compress rather than {@code java.util.zip} because entry unix modes have to be
 * settable — a symlink entry is only a symlink because its mode says so, and {@code java.util.zip}
 * cannot express that, which is precisely why the validator needs commons-compress to detect one.
 */
public final class Zips {

    private Zips() {
    }

    private static final int REGULAR_FILE = 0100644;
    private static final int DIRECTORY = 040755;
    private static final int SYMLINK = 0120777;
    /** A regular file whose owner may execute it — the bit a skill's script needs (v51). */
    private static final int EXECUTABLE_FILE = 0100755;

    /**
     * A zip of regular files, keyed by path. Insertion order is preserved.
     *
     * <p>Both this and {@link #ofRawPaths} rewrite {@code \} as {@code /} while writing — the ZIP
     * spec mandates {@code /} as the separator, so neither tool will produce a backslash entry.
     * To test the reader's rejection of one, build with {@code /} and then rewrite the bytes with
     * {@link #patchedNames}.
     */
    public static byte[] of(Map<String, byte[]> entries) {
        return build(out -> entries.forEach((path, bytes) -> write(out, path, bytes, REGULAR_FILE)));
    }

    /**
     * Rewrites occurrences of one entry name to another of the same length, byte for byte.
     *
     * <p>Equal length is required and checked: the name appears in both the local header and the
     * central directory, and changing its length would invalidate every offset in the archive.
     * Same-length rewriting is what makes a shape no archive writer will produce reachable in a
     * test — currently only the backslash.
     */
    public static byte[] patchedNames(byte[] zip, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("names must be the same length: " + from + " / " + to);
        }
        byte[] needle = from.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = to.getBytes(StandardCharsets.UTF_8);
        byte[] patched = zip.clone();

        int replacements = 0;
        for (int i = 0; i + needle.length <= patched.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length && matches; j++) {
                matches = patched[i + j] == needle[j];
            }
            if (matches) {
                System.arraycopy(replacement, 0, patched, i, replacement.length);
                replacements++;
                i += needle.length - 1;
            }
        }
        if (replacements == 0) {
            throw new IllegalArgumentException("no entry named " + from + " in this archive");
        }
        return patched;
    }

    /**
     * Rewrites one entry's declared uncompressed size, in the central directory.
     *
     * <p>How a zip bomb lies: the header claims a few bytes and the deflate stream delivers many. No
     * archive writer produces that — the declared size is computed on the way out — so it has to be
     * patched in afterwards, the same reason {@link #patchedNames} exists. Only the central
     * directory is touched, because that is the copy a random-access reader believes.
     *
     * <p>The directory is found by scanning for its signature and checking the name at each
     * candidate, so a chance occurrence of those four bytes inside compressed data is skipped rather
     * than trusted.
     */
    public static byte[] withDeclaredSize(byte[] zip, String entryName, int declaredSize) {
        byte[] patched = zip.clone();
        for (int at = 0; at + 46 <= patched.length; at++) {
            if (patched[at] != 'P' || patched[at + 1] != 'K'
                    || patched[at + 2] != 1 || patched[at + 3] != 2) {
                continue;
            }
            int nameLength = readShort(patched, at + 28);
            if (nameLength <= 0 || at + 46 + nameLength > patched.length) {
                continue;
            }
            if (new String(patched, at + 46, nameLength, StandardCharsets.UTF_8).equals(entryName)) {
                writeInt(patched, at + 24, declaredSize);
                return patched;
            }
        }
        throw new IllegalArgumentException("no central directory entry named " + entryName);
    }

    private static int readShort(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8);
    }

    private static void writeInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    /**
     * A zip whose entry names are written as given, using {@code java.util.zip}.
     *
     * <p>Needed for the names commons-compress will not write at all: {@code ../evil.md} and
     * {@code /etc/passwd} both survive here and neither survives {@link #of}. Backslashes do not
     * survive either way — see {@link #patchedNames}.
     *
     * <p>No unix modes here — {@code java.util.zip} cannot express them — so this cannot build a
     * symlink entry. That is what {@link #withSymlink} is for, and the two exist side by side
     * because each tool can express one of the two things the reader has to detect.
     */
    public static byte[] ofRawPaths(java.util.Map<String, byte[]> entries) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The same, with one entry marked executable.
     *
     * <p>Used to prove the bit survives the round trip: upload → {@code version_file.mode} →
     * archive → extraction. Everything else stays a regular file, which is what makes a mismatch in
     * the expectation visible rather than absorbed.
     */
    public static byte[] ofTextWithExecutable(Map<String, String> entries, String executablePath) {
        return build(out -> entries.forEach((path, text) -> write(out, path,
                text.getBytes(StandardCharsets.UTF_8),
                path.equals(executablePath) ? EXECUTABLE_FILE : REGULAR_FILE)));
    }

    public static byte[] ofText(Map<String, String> entries) {
        Map<String, byte[]> asBytes = new LinkedHashMap<>();
        entries.forEach((path, text) -> asBytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return of(asBytes);
    }

    /** A zip holding one symlink entry alongside nothing else. */
    public static byte[] withSymlink(String linkPath, String target) {
        return build(out -> write(out, linkPath, target.getBytes(StandardCharsets.UTF_8), SYMLINK));
    }

    /** A zip holding one directory entry, which readers must ignore. */
    public static byte[] withDirectoryEntry(String path, Map<String, byte[]> files) {
        return build(out -> {
            write(out, path.endsWith("/") ? path : path + "/", new byte[0], DIRECTORY);
            files.forEach((file, bytes) -> write(out, file, bytes, REGULAR_FILE));
        });
    }

    private interface ZipBody {
        void write(ZipArchiveOutputStream out) throws IOException;
    }

    private static byte[] build(ZipBody body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            body.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipArchiveOutputStream out, String path, byte[] content, int mode) {
        try {
            ZipArchiveEntry entry = new ZipArchiveEntry(path);
            entry.setUnixMode(mode);
            out.putArchiveEntry(entry);
            out.write(content);
            out.closeArchiveEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

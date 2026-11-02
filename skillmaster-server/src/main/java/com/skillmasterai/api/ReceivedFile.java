package com.skillmasterai.api;

import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.Utf8Probe;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One uploaded file, held in memory when it is small and on disk when it is not (v39).
 *
 * <p>The boundary is not a product limit; it is the size below which buffering is cheaper than a
 * temporary file. Above it, the request is copied straight to disk, the binary probe reads that
 * file, and the blob store uploads from the same file — one copy, no matter how large the upload.
 *
 * <p>The file is the caller's to close; a failed request deletes it in a {@code finally}, and a
 * successful one leaves it alone until the same {@code finally} runs after the store has read it.
 */
final class ReceivedFile implements AutoCloseable {

    /** One mebibyte: below this, the file is a byte array like every write before v39. */
    static final long MEMORY_THRESHOLD = 1024L * 1024L;

    private final byte[] bytes;
    private final Path file;
    private final long size;
    private final boolean binary;

    private ReceivedFile(byte[] bytes, Path file, long size, boolean binary) {
        this.bytes = bytes;
        this.file = file;
        this.size = size;
        this.binary = binary;
    }

    /**
     * @param relpath the file this body will be written to, for the refusal's `field` — the routes
     *                that call this all have the path, and a size refusal that cannot name the file
     *                makes the client fall back to a sentence without it (v39, voice-spec)
     */
    static ReceivedFile read(HttpServletRequest request, long maxFileBytes, java.nio.file.Path tempDir,
            String relpath) {
        long declared = request.getContentLengthLong();
        if (declared > maxFileBytes) {
            throw tooLarge(declared, maxFileBytes, relpath);
        }
        if (declared >= 0 && declared <= MEMORY_THRESHOLD) {
            byte[] bytes = readBounded(request, MEMORY_THRESHOLD);
            if (bytes.length <= MEMORY_THRESHOLD) {
                return new ReceivedFile(bytes, null, bytes.length,
                        new com.skillmasterai.modules.ingest.IngestedFile("", bytes).isBinary());
            }
            return spool(request, bytes, maxFileBytes, tempDir, relpath);
        }
        return spool(request, new byte[0], maxFileBytes, tempDir, relpath);
    }

    boolean inMemory() {
        return bytes != null;
    }

    byte[] bytes() {
        return bytes;
    }

    Path file() {
        return file;
    }

    long size() {
        return size;
    }

    boolean binary() {
        return binary;
    }

    @Override
    public void close() {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The request is over; a leftover temporary file is the operating system's to reap.
        }
    }

    private static byte[] readBounded(HttpServletRequest request, long ceiling) {
        try {
            // Not closed here: a stream that turns out to be longer than the prefix is continued
            // by spool(), which reads the same request stream to its end.
            return request.getInputStream().readNBytes((int) ceiling + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("the request body could not be read", e);
        }
    }

    private static ReceivedFile spool(HttpServletRequest request, byte[] prefix, long maxFileBytes,
            java.nio.file.Path tempDir, String relpath) {
        Path temp = null;
        try {
            Files.createDirectories(tempDir);
            temp = Files.createTempFile(tempDir, "skillmaster-upload-", ".bin");
            long total = prefix.length;
            if (total > maxFileBytes) {
                throw tooLarge(total, maxFileBytes, relpath);
            }
            try (OutputStream out = Files.newOutputStream(temp)) {
                out.write(prefix);
                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = request.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > maxFileBytes) {
                            throw tooLarge(total, maxFileBytes, relpath);
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
            return new ReceivedFile(null, temp, total, Utf8Probe.isBinary(temp));
        } catch (IOException e) {
            delete(temp);
            throw new UncheckedIOException("the upload could not be spooled", e);
        } catch (RuntimeException e) {
            delete(temp);
            throw e;
        }
    }

    private static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort on a path that is already being abandoned.
        }
    }

    static IngestException tooLarge(long actualBytes, long maxFileBytes) {
        return tooLarge(actualBytes, maxFileBytes, "file");
    }

    /**
     * The same refusal, naming the file.
     *
     * <p>{@code field} carries the path — the reading v33's collision details already use — so both
     * clients can write 「文件 <名字> 有 <实际> MiB，超过单文件上限 <上限> MiB。」 instead of a
     * sentence that cannot say which file it is about (v39, voice-spec §上传与大小的四句话).
     */
    static IngestException tooLarge(long actualBytes, long maxFileBytes, String relpath) {
        return new IngestException("the file is " + com.skillmasterai.modules.ingest.IngestLimits
                .mib(actualBytes) + ", above the per-file limit of "
                + com.skillmasterai.modules.ingest.IngestLimits.mib(maxFileBytes),
                java.util.List.of(new com.skillmasterai.common.ApiError.Detail(
                        relpath == null || relpath.isBlank() ? "file" : relpath,
                        "file_too_large", actualBytes, maxFileBytes)));
    }
}

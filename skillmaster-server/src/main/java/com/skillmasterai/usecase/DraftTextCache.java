package com.skillmasterai.usecase;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Decoded text for blobs a grep or a count is reading, bounded and keyed by content.
 *
 * <p><strong>A cache with no invalidation problem.</strong> A blob is immutable — its digest is its
 * identity (ADR 0005) — so a decoded copy cannot go stale. What it buys is the second and later
 * scans of the same 16 MiB version, which is the shape of an agent's loop ("grep, read, grep
 * again"); what it costs is memory, bounded here rather than left to the JVM's sense of occasion.
 *
 * <p>Not a correctness component: an evicted entry is decoded again. That is also why it lives at
 * the use-case layer rather than inside M6 — it is a decision about this operation's cost, not
 * about where bytes live.
 */
@Component
public class DraftTextCache {

    /** The budget the design names (v7 TD §grep 的作用域与上限). */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final Map<String, String> texts = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;

    /**
     * @param sha  the blob's digest, the key
     * @param load how to get the bytes when this is a miss
     */
    public synchronized String text(String sha, Supplier<byte[]> load) {
        String cached = texts.get(sha);
        if (cached != null) {
            return cached;
        }
        String decoded = new String(load.get(), StandardCharsets.UTF_8);
        // Bytes of the decoded string, which is what the budget is about — the blob's own size
        // would under-count a multi-byte file's char array and over-count nothing.
        long cost = decoded.length() * 2L;
        if (cost > MAX_BYTES) {
            // A single file over the budget is returned but never retained: caching it would evict
            // everything else for the one entry that will not fit twice.
            return decoded;
        }
        texts.put(sha, decoded);
        bytes += cost;
        var iterator = texts.entrySet().iterator();
        while (bytes > MAX_BYTES && iterator.hasNext()) {
            var eldest = iterator.next();
            bytes -= eldest.getValue().length() * 2L;
            iterator.remove();
        }
        return decoded;
    }
}

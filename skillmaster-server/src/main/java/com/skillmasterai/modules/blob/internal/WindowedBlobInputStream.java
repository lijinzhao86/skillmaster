package com.skillmasterai.modules.blob.internal;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One blob's bytes, fetched one {@code substring} window at a time.
 *
 * <p>Deliberately not a {@code ResultSet} held open: the JDBC client borrows a connection per
 * statement, and a response that streams while holding one would occupy a pool slot for exactly as
 * long as the client is slow — the opposite of what this exists for. A window is fetched, handed
 * out, and the connection returned before the caller asks for the next.
 *
 * <p>Immutable content is the assumption that makes this sound: a blob's bytes never change after
 * the row exists, so a later window is the same file's later bytes even though it arrives in a new
 * statement. A close stops all further fetches.
 */
final class WindowedBlobInputStream extends InputStream {

    /** The window size the design's budget is written against (v7 TD §流式与提前退出). */
    private static final int WINDOW = 32 * 1024;

    private final JdbcClient jdbc;
    private final String sha256;
    private final long size;

    /** Where the next byte comes from, in the blob. */
    private long next;

    private byte[] window = new byte[0];
    private int windowOffset;
    private boolean closed;

    WindowedBlobInputStream(JdbcClient jdbc, String sha256, long size) {
        this.jdbc = jdbc;
        this.sha256 = sha256;
        this.size = size;
    }

    @Override
    public int read() throws IOException {
        if (!ensureWindow()) {
            return -1;
        }
        return window[windowOffset++] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
            return 0;
        }
        if (!ensureWindow()) {
            return -1;
        }
        int available = window.length - windowOffset;
        int count = Math.min(length, available);
        System.arraycopy(window, windowOffset, target, offset, count);
        windowOffset += count;
        return count;
    }

    /**
     * @return whether bytes are ready; false at the blob's end
     */
    private boolean ensureWindow() throws IOException {
        if (closed) {
            // The InputStream contract: reading a closed stream is an error, not the end of the
            // data. Returning false here made `read()` look like EOF after close while
            // `read(byte[], …)` threw — two answers for one state.
            throw new IOException("stream closed");
        }
        if (windowOffset < window.length) {
            return true;
        }
        if (next >= size) {
            return false;
        }
        int length = (int) Math.min(WINDOW, size - next);
        try {
            // substring() is 1-based and length-bounded; both parameters are ours, never the
            // client's, so there is no path from a request to this statement.
            // The casts are load-bearing: the driver binds an int as bigint, and PostgreSQL has
            // no substring(bytea, bigint, bigint) — it would report that the function does not
            // exist, which reads like a missing extension rather than a parameter type.
            window = jdbc.sql("SELECT substring(bytes FROM CAST(:from AS integer)"
                            + " FOR CAST(:length AS integer)) AS chunk"
                            + " FROM blob_content WHERE sha256 = :sha256")
                    .param("from", next + 1)
                    .param("length", length)
                    .param("sha256", sha256)
                    .query((rs, rowNum) -> rs.getBytes("chunk"))
                    .optional()
                    .orElse(null);
        } catch (RuntimeException e) {
            throw new IOException("reading blob " + sha256 + " at offset " + next, e);
        }
        windowOffset = 0;
        if (window == null || window.length == 0) {
            // A row that vanished mid-stream. Reported as the end rather than as an error: the
            // bytes already sent are intact, and a client that saw a truncated file would otherwise
            // have to tell "short blob" from "broken connection" with no way to do it.
            window = new byte[0];
            next = size;
            return false;
        }
        next += window.length;
        return true;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, window.length - windowOffset));
    }

    @Override
    public void close() {
        closed = true;
        window = new byte[0];
        next = size;
    }

    @Override
    public String toString() {
        // Never the bytes — this is what an exception message or a debugger shows.
        return "WindowedBlobInputStream[" + sha256 + ", " + next + "/" + size + "]";
    }

    /** Package-private for a test that wants to assert the window's bound without the bytes. */
    static int windowSize() {
        return WINDOW;
    }
}

package com.skillmasterai.usecase.model;

/**
 * One edit the editor can apply to a draft: write a file, delete one, or rename one.
 *
 * <p>The three shapes the editor's writes share (v8 TD §接口). A record rather than three request
 * classes because the operation is one thing with one set of fields; the unused ones are null.
 */
public record DraftEdit(String op, String path, String text, String from, String to) {

    public static final String WRITE = "write";
    public static final String DELETE = "delete";
    public static final String RENAME = "rename";

    public static DraftEdit write(String path, String text) {
        return new DraftEdit(WRITE, path, text, null, null);
    }

    public static DraftEdit delete(String path) {
        return new DraftEdit(DELETE, path, null, null, null);
    }

    public static DraftEdit rename(String from, String to) {
        return new DraftEdit(RENAME, null, null, from, to);
    }

    /**
     * The path whose place in the tree this edit changes — the write target or the rename target.
     *
     * <p>Null for a delete: removing a file cannot make a file and a directory share a path. Checking
     * the removed path anyway would refuse a delete in a v7 draft that already holds both {@code a}
     * and {@code a/b} — data this version deliberately does not clean up (v8 TD §接口).
     */
    public String targetPath() {
        return switch (op == null ? WRITE : op) {
            case RENAME -> to;
            case DELETE -> null;
            default -> path;
        };
    }
}

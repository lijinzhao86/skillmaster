package com.skillmasterai.usecase.sync;

import java.util.List;

/**
 * One skill-shaped thing the sync is asked to look at.
 *
 * <p>{@code select} is how one configured line becomes many entries: {@link Mode#ONE} is the line
 * itself, {@link Mode#ALL} expands to every direct child of {@code path}, and {@link Mode#NAMES}
 * expands to the children the line named. Expansion happens before batching, so concurrency and the
 * log still count one skill at a time.
 *
 * @param path        the directory the skill lives in, relative to the container's root; empty
 *                    means the container itself is the skill
 * @param names       the direct children {@link Mode#NAMES} selected; empty for the other two modes
 * @param licensePath a repository-relative license file to use instead of the nearest one; empty
 *                    means "find the nearest license on the ancestry"
 * @param naming      optional address change (ADR 0160); {@link Naming#NONE} means "keep upstream"
 */
public record SyncEntry(String kind, String locator, String path, Mode mode, List<String> names,
        String licensePath, Naming naming) {

    public enum Mode { ONE, ALL, NAMES }

    /** Optional address change: a source prefix, or an explicit name for a single-skill entry. */
    public record Naming(String prefix, String explicitName) {

        public static final Naming NONE = new Naming("", "");

        public Naming {
            prefix = prefix == null ? "" : prefix.trim();
            explicitName = explicitName == null ? "" : explicitName.trim();
        }
    }

    public SyncEntry {
        path = path == null ? "" : path.trim();
        names = names == null ? List.of() : List.copyOf(names);
        licensePath = licensePath == null ? "" : licensePath.trim();
        naming = naming == null ? Naming.NONE : naming;
    }

    public SyncEntry(String kind, String locator, String path, Mode mode, List<String> names,
            String licensePath) {
        this(kind, locator, path, mode, names, licensePath, Naming.NONE);
    }

    /** One direct child of this entry, as its own single-skill entry. */
    public SyncEntry child(String name) {
        return new SyncEntry(kind, locator, path.isEmpty() ? name : path + "/" + name,
                Mode.ONE, List.of(), licensePath, naming);
    }

    /** How the entry is written in the log. */
    public String display() {
        return kind + ":" + locator + (path.isEmpty() ? "" : "@" + path);
    }
}

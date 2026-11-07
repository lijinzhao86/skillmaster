package com.skillmasterai.config;

import com.skillmasterai.usecase.sync.SkillNameTransform;
import com.skillmasterai.usecase.sync.SyncEntry;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads {@code sync-sources.yml} and rejects anything the pipeline could misread.
 *
 * <p>The file is the product's whitelist: there is no automatic discovery, so a bad entry is a
 * configuration mistake and must fail startup rather than silently sync nothing (ADR 0155). Values
 * are validated here; whether a {@code kind} exists is checked after the adapter registry is built.
 */
public final class SyncSourcesLoader {

    private static final Set<String> ROOT_KEYS = Set.of("version", "sources");
    private static final Set<String> SOURCE_KEYS =
            Set.of("kind", "locator", "path", "select", "license", "prefix", "name");

    private final ResourceLoader resources;

    public SyncSourcesLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    @SuppressWarnings("unchecked")
    public List<SyncEntry> load(String location) {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("the sync sources file does not exist: " + location);
        }
        Object loaded;
        try (InputStream input = resource.getInputStream()) {
            loaded = new Yaml().load(input);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "the sync sources file could not be read: " + location, failure);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalStateException("the sync sources file must be a mapping");
        }
        rejectUnknown(root, ROOT_KEYS, "the sync sources file");
        Object version = root.get("version");
        /* Exact, not intValue(): 1.5 would truncate to 1 and pass a check whose whole job is to
           refuse a format this build does not understand. */
        if (!(version instanceof Number number) || number.doubleValue() != 1.0) {
            throw new IllegalStateException(
                    "the sync sources file must declare version: 1 (found " + version + ")");
        }
        Object rawSources = root.get("sources");
        if (!(rawSources instanceof List<?> sources) || sources.isEmpty()) {
            throw new IllegalStateException("the sync sources file lists no sources");
        }
        List<SyncEntry> entries = new ArrayList<>();
        int index = 0;
        for (Object raw : sources) {
            index++;
            if (!(raw instanceof Map<?, ?> source)) {
                throw new IllegalStateException("sync source #" + index + " must be a mapping");
            }
            entries.add(entry((Map<String, Object>) source, index));
        }
        return entries;
    }

    private static SyncEntry entry(Map<String, Object> source, int index) {
        rejectUnknown(source, SOURCE_KEYS, "sync source #" + index);
        String kind = string(source.get("kind"), index, "kind");
        if (kind == null) {
            throw new IllegalStateException("sync source #" + index + " has no kind");
        }
        String locator = string(source.get("locator"), index, "locator");
        if (locator == null) {
            throw new IllegalStateException("sync source #" + index + " has no locator");
        }
        String path = string(source.get("path"), index, "path");
        path = path == null ? "" : path;
        validatePath(path, index, "path");
        String license = string(source.get("license"), index, "license");
        license = license == null ? "" : license;
        validatePath(license, index, "license");

        String prefix = string(source.get("prefix"), index, "prefix");
        String explicitName = string(source.get("name"), index, "name");
        /* A present-but-empty key is a typo, not "no prefix": treating it as absent silently
           turns off the rename the operator believes they configured. */
        if (prefix == null && source.containsKey("prefix")) {
            throw new IllegalStateException("sync source #" + index
                    + " has an empty prefix; remove the key or give it a value");
        }
        if (explicitName == null && source.containsKey("name")) {
            throw new IllegalStateException("sync source #" + index
                    + " has an empty name; remove the key or give it a value");
        }
        if (prefix != null && explicitName != null) {
            throw new IllegalStateException("sync source #" + index
                    + " has both prefix and name; choose one (ADR 0160)");
        }
        requireNameComponent(prefix, index, "prefix");
        requireNameComponent(explicitName, index, "name");
        SyncEntry.Naming naming = new SyncEntry.Naming(prefix, explicitName);

        Object select = source.get("select");
        if (select == null) {
            return new SyncEntry(kind, locator, path, SyncEntry.Mode.ONE, List.of(), license,
                    naming);
        }
        if (explicitName != null) {
            throw new IllegalStateException("sync source #" + index
                    + " has name with select; a select entry needs a prefix, not one name");
        }
        if (select instanceof String value) {
            if (!"all".equals(value.trim())) {
                throw new IllegalStateException("sync source #" + index
                        + " has select: " + value + "; the only string form is select: all");
            }
            requirePath(path, index, "select: all");
            return new SyncEntry(kind, locator, path, SyncEntry.Mode.ALL, List.of(), license,
                    naming);
        }
        if (select instanceof List<?> names) {
            requirePath(path, index, "select: [names]");
            List<String> selected = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object rawName : names) {
                if (!(rawName instanceof String name) || name.isBlank()) {
                    throw new IllegalStateException(
                            "sync source #" + index + " has a blank select name");
                }
                String clean = name.trim();
                if (clean.contains("/") || clean.contains("\\") || clean.contains("..")
                        || clean.startsWith(".")) {
                    throw new IllegalStateException("sync source #" + index
                            + " has an invalid select name '" + clean
                            + "'; it must be one visible directory name");
                }
                if (!seen.add(clean)) {
                    throw new IllegalStateException("sync source #" + index
                            + " names '" + clean + "' twice in select");
                }
                selected.add(clean);
            }
            if (selected.isEmpty()) {
                throw new IllegalStateException(
                        "sync source #" + index + " has an empty select list");
            }
            return new SyncEntry(kind, locator, path, SyncEntry.Mode.NAMES, selected, license,
                    naming);
        }
        throw new IllegalStateException("sync source #" + index
                + " has a select that is neither all nor a list of names");
    }

    private static void requirePath(String path, int index, String what) {
        if (path.isEmpty()) {
            throw new IllegalStateException(
                    "sync source #" + index + " uses " + what + " but has no path");
        }
    }

    private static void requireNameComponent(String value, int index, String key) {
        if (value == null) {
            return;
        }
        try {
            SkillNameTransform.requireValidComponent(value);
        } catch (SkillNameTransform.InvalidName invalid) {
            throw new IllegalStateException("sync source #" + index + " has an invalid " + key
                    + " '" + value + "': " + invalid.reason());
        }
    }

    private static void validatePath(String path, int index, String what) {
        if (path.isEmpty()) {
            return;
        }
        if (path.endsWith("/")) {
            throw new IllegalStateException("sync source #" + index + " has a " + what
                    + " ending in '/' ('" + path + "'); drop the trailing slash");
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")
                || path.contains("..")) {
            throw new IllegalStateException("sync source #" + index
                    + " has an invalid " + what + " '" + path + "'; it must be relative");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalStateException("sync source #" + index
                        + " has an invalid " + what + " '" + path + "'");
            }
        }
    }

    private static void rejectUnknown(Map<?, ?> mapping, Set<String> allowed, String where) {
        for (Object key : mapping.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalStateException(where + " has an unknown key '" + key
                        + "'; allowed keys: " + allowed);
            }
        }
    }

    /**
     * A required-or-optional string field.
     *
     * <p>{@code toString()} would turn a list or a map into a plausible-looking path — a startup
     * check whose job is to refuse a configuration this build does not understand must not accept
     * one by accident.
     */
    private static String string(Object value, int index, String key) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException("sync source #" + index + " has a non-string " + key
                    + " (" + value + ")");
        }
        return text.isBlank() ? null : text.trim();
    }
}

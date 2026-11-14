package com.skillmasterai.ops.imports;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.Yaml;

/**
 * The rule file, compiled once at startup.
 *
 * <p>A bad regex or a missing field fails startup rather than silently skipping a rule: a scan that
 * quietly checks less than it says is worse than a server that refuses to start.
 */
public record SecurityRules(List<SecurityRule> blocked, List<SecurityRule> warnings) {

    public SecurityRules {
        blocked = List.copyOf(blocked);
        warnings = List.copyOf(warnings);
    }

    @SuppressWarnings("unchecked")
    public static SecurityRules load(InputStream yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalStateException("sync/security-rules.yml must be a mapping");
        }
        List<SecurityRule> blocked = rules((List<Object>) root.get("blocked"), "blocked");
        List<SecurityRule> warnings = rules((List<Object>) root.get("warnings"), "warnings");
        if (blocked.isEmpty()) {
            throw new IllegalStateException("sync/security-rules.yml lists no blocking rule");
        }
        return new SecurityRules(blocked, warnings);
    }

    @SuppressWarnings("unchecked")
    private static List<SecurityRule> rules(List<Object> entries, String section) {
        List<SecurityRule> rules = new ArrayList<>();
        if (entries == null) {
            return rules;
        }
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("each " + section + " rule must be a mapping");
            }
            String id = text(entry.get("id"));
            if (id == null) {
                throw new IllegalStateException("a " + section + " rule has no id");
            }
            String label = text(entry.get("label"));
            String kindText = text(entry.get("kind"));
            SecurityRule.Kind kind = kindOf(kindText, section, id);
            String regex = text(entry.get("regex"));
            if (regex == null && (kind == SecurityRule.Kind.TEXT
                    || kind == SecurityRule.Kind.PATH)) {
                throw new IllegalStateException(
                        "sync rule '" + id + "' has no regex; only binary and executable rules"
                                + " may omit one");
            }
            Pattern pattern;
            try {
                pattern = regex == null ? null : Pattern.compile(regex);
            } catch (PatternSyntaxException failure) {
                throw new IllegalStateException(
                        "sync rule '" + id + "' has an invalid regex: " + failure.getMessage(),
                        failure);
            }
            rules.add(new SecurityRule(id, label == null ? id : label, kind, pattern));
        }
        return rules;
    }

    private static SecurityRule.Kind kindOf(String value, String section, String id) {
        if (value == null || value.isBlank()) {
            return SecurityRule.Kind.TEXT;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "text" -> SecurityRule.Kind.TEXT;
            case "path" -> SecurityRule.Kind.PATH;
            case "binary" -> SecurityRule.Kind.BINARY;
            case "executable" -> SecurityRule.Kind.EXECUTABLE;
            case "executable_binary" -> SecurityRule.Kind.EXECUTABLE_BINARY;
            default -> throw new IllegalStateException(
                    "sync rule '" + id + "' in " + section + " has unknown kind '" + value + "'");
        };
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
